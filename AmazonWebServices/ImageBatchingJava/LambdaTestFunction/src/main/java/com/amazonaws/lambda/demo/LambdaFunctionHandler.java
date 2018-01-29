package com.amazonaws.lambda.demo;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClients;

import com.amazonaws.lambda.demo.EdgeHashResults.MatchResults;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.event.S3EventNotification.S3Entity;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.google.gson.Gson;

import PhotoDNA.PDNAClientHashGenerator;
import PhotoDNA.PDNAClientHashResult;

public class LambdaFunctionHandler implements RequestStreamHandler {

	private static String queueName = "testqueuePDNA";
	private static String qurueUrl = null;
	private final String JPG_TYPE = (String) "jpg";
	private final String PNG_TYPE = (String) "png";

	@Override
	public void handleRequest(InputStream input, OutputStream outputStream, Context context) throws IOException {
		HashSet<String> recievedKeys = new HashSet<String>();
		/// TODO: CONNECT
		
		AmazonSQS sqsClient = AmazonSQSClientBuilder.defaultClient();
		LambdaLogger logger = context.getLogger();
		logger.log("Start ....");
		
		GetQueueUrlRequest req = new GetQueueUrlRequest().withQueueName(queueName);

		qurueUrl = sqsClient.getQueueUrl(req).getQueueUrl();

		Boolean endOfQueue = false;

		LocalTime localTime = LocalTime.now();

		LocalTime endtime = LocalTime.now().plusMinutes(3).plusSeconds(30);

		while (!localTime.isAfter(endtime) && !endOfQueue) {
			LinkedList<com.amazonaws.services.sqs.model.Message> messages = new LinkedList<>();
			// get as many messages as possible then split
			for (int i = 0; i < 5 && !endOfQueue; i++) {
				ReceiveMessageRequest sendRequest = createMessageRequest(qurueUrl);
				// max of ten per request
				ReceiveMessageResult res = sqsClient.receiveMessage(sendRequest);
				if (res.getMessages().size() == 0) {
					logger.log("Reached End of Queue \n");
					endOfQueue = true;
				} else {
					logger.log("Log messages \n");
					for (com.amazonaws.services.sqs.model.Message mess : res.getMessages()) {
						if (!recievedKeys.contains(mess.getReceiptHandle())) {
							messages.addLast(mess);
							recievedKeys.add(mess.getReceiptHandle());
						}
					}
				}
			}

			// messages now has at most 50 messages
			ConcurrentLinkedQueue<List<HashedImage>> hashGroups = new ConcurrentLinkedQueue<>();
			while (messages.size() > 0) {

				List<HashedImage> binHash = new LinkedList<HashedImage>();
				for (int j = 0; j < 5 && messages.size() > 0; j++) {
					// ReadMessage
					com.amazonaws.services.sqs.model.Message message = null;
					try {
						message = messages.poll();
						String body = message.getBody();
						S3EventNotification s3event = S3EventNotification.parseJson(body);
						List<S3EventNotificationRecord> records = s3event.getRecords();
						Boolean gotS3Blob = false;
						for (int k = 0; k < records.size(); k++) {
							try {
								
								//read Event
								S3EventNotificationRecord record = records.get(k);
								S3Entity entity = record.getS3();

								String bucket = entity.getBucket().getName();
								String key = entity.getObject().getUrlDecodedKey();
								
								Matcher matcher = Pattern.compile(".*\\.([^\\.]*)").matcher(key);
								Boolean isImage = true;
								if (!matcher.matches()) {

									isImage = false;
								}
								String imageType = matcher.group(1);
								// TODO: Other image types
								if (!(JPG_TYPE.equalsIgnoreCase(imageType))
										&& !(PNG_TYPE.equalsIgnoreCase(imageType))) {
									logger.log("Skipping non-image " + key);
									isImage = false;
								}

								if (isImage) {
									binHash.add(new HashedImage(bucket, key, message.getReceiptHandle()));
								}
								else {
									logger.log("DLETE NON IMAGE FROM QUEUE \n");
									sqsClient.deleteMessage(qurueUrl,message.getReceiptHandle());
								}
								gotS3Blob = true;
							} catch (Exception e) {
								// S3Request Object did not have expected properites
								// logException(logger, e);
								if (k == records.size() - 1 && !gotS3Blob) {
									throw new Exception("Full failure to parse body:" + body, e);
								}
							}
						}
					} catch (Exception e) {
						// Failed to read individual blob
						// TODO: Should delete message from queue???
						logger.log("FROM read blob");
						logException(logger, e);
						if (message != null) {
							sqsClient.deleteMessage(qurueUrl, message.getReceiptHandle());
						}
					}
				}
				// Now have set of 5
				hashGroups.add(binHash);
			}

			while (hashGroups.size() > 0) {
				List<Thread> requstThreads = new LinkedList<>();
				Thread sleep = new Thread(new ThreadSleep());
				requstThreads.add(sleep);

				for (int j = 0; j < 10 && hashGroups.size() > 0; j++) {
					List<HashedImage> cur = hashGroups.poll();
					Thread request = new Thread(new EdgeHashRequest(cur, sqsClient, outputStream, logger));
					requstThreads.add(request);
					request.start();
				}
				sleep.start();

				for (Thread t : requstThreads) {
					try {
						t.join();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}

		BufferedReader textReader = new BufferedReader(new InputStreamReader(input));

		String contents = textReader.lines().collect(Collectors.joining("\n"));
		

		// TODO: implement your handler
		OutputStreamWriter writter = new OutputStreamWriter(outputStream);
		writter.write(contents);
		writter.close();
	}

	private static void logException(LambdaLogger logger, Throwable e) {
		logger.log("Lamdba PDNATimer ERROR" + e.getMessage());
	}

	
	public static void SendEmail(String message, String to, String subject, LambdaLogger logger) {
		try {
		AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder.defaultClient();
		SendEmailRequest req = new SendEmailRequest()
			.withDestination(new Destination().withToAddresses(to))
			.withMessage(new com.amazonaws.services.simpleemail.model.Message()
					.withBody(new Body()
							.withText( new Content()
									.withCharset("UTF-8").withData(message))
							)
					.withSubject(new Content()
							.withCharset("UTF-8").withData(subject))
			)
			.withSource("ryan.leenay@allyis.com");
		client.sendEmail(req);
		logger.log("Email Sent");
		}
		catch(Exception e){
			logException(logger, e);
		}
		
	}
	
	public class HashedImage {
		public String key;
		public String bucket;
		public String recieptHandler;

		public HashedImage( String bucketName, String key, String reciept) {
			this.key = key;
			this.bucket = bucketName;
			this.recieptHandler = reciept;
		}
	}

	private ReceiveMessageRequest createMessageRequest(String url) {
		ReceiveMessageRequest sendRequest = new ReceiveMessageRequest().withMaxNumberOfMessages(10).withQueueUrl(url)
				.withWaitTimeSeconds(2).withVisibilityTimeout(30);// Visibilty of 3 minites
		return sendRequest;
	}

	public class ThreadSleep implements Runnable {

		@Override
		public void run() {
			// TODO Auto-generated method stub
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}

	}

	public class EdgeHashRequest implements Runnable, Callable<Object> {

		OutputStream outputStream;
		List<HashedImage> images;
		LambdaLogger log;
		AmazonSQS queue;
		
		EdgeHashRequest(List<HashedImage> images, AmazonSQS queue, OutputStream outputStream, LambdaLogger log) {
			this.images = images;
			this.outputStream = outputStream;
			this.log = log;
			this.queue = queue;
		}

		private void makeEdgeHashRequest() throws IOException, URISyntaxException {

			// TODO: Handle request
			AmazonS3ClientBuilder builder =  AmazonS3ClientBuilder.standard()
					.withRegion(Regions.US_WEST_2);
					
			AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
			
			HttpClient httpclient = HttpClients.createDefault();

			URIBuilder urib = new URIBuilder("https://apim.projectwabash.com/photodna/v1.0/MatchEdgeHash");

			URI uri = urib.build();

			HttpPost request = new HttpPost(uri);
			MultipartEntityBuilder multipart = MultipartEntityBuilder.create();
			
			request.setHeader("Ocp-Apim-Subscription-Key", "884a6e4f5cc74558a32d5c759dd43e70");
			request.setHeader("Cache-Control", "no-cache");
			
			for (HashedImage im : images) {
				try {
					S3Object object = s3Client.getObject(new GetObjectRequest(im.bucket, im.key)
							.withBucketName(im.bucket)
							.withKey(im.key)
							);
					BufferedImage image = ImageIO.read(object.getObjectContent());
					
					PDNAClientHashResult preHashObject = PDNAClientHashGenerator.generatePreHash(image);
					multipart.addBinaryBody(im.recieptHandler, preHashObject.generateBinaryPreHash());

				} catch (Exception e) {
					//failed to get prehash for image
					
					e.printStackTrace();
				}
			}

			request.setEntity(multipart.build());

			
			HttpResponse response = httpclient.execute(request);
			HttpEntity responseEntity = response.getEntity();
			
			log.log(response.getStatusLine().getReasonPhrase() + "\n");
			
			if(response.getStatusLine().getStatusCode() != 200) {
				//HTTP ERROR
				log.log("HTTTP ERRROR " + "\n" );
				BufferedReader textReader = new BufferedReader(new InputStreamReader(responseEntity.getContent()));
				
				String contents = textReader.lines().collect(Collectors.joining("\n"));
				log.log(contents + "\n");
			}
			else {
				//NO Error
				Gson gson = new Gson();
				EdgeHashResults results = gson.fromJson(new InputStreamReader(responseEntity.getContent()), EdgeHashResults.class);
				
				for(int i =0; i < results.MatchResults.length; i++) {
					//request successfull sent
					MatchResults res = results.MatchResults[i];
					if(res.Status.Code != 3000) {
						//Request Error 
						log.log("Request Error CODE" + res.Status.Code + "\n");
						log.log("Description" + res.Status.Desription + "\n");
						log.log("Exception " + res.Status.Exception.toString() + "\n");
						//TODO: DETERMINE IF DELETE MESSAGE WORKS HERE
					}
					else {
						//Successful request from PDNA no need to hold on to message
						queue.deleteMessage(new DeleteMessageRequest(LambdaFunctionHandler.qurueUrl, res.ContentId));
						log.log("HTTTP Success \n");;
						if(res.IsMatch) {
							HashedImage image = getImage(images, res.ContentId);
							log.log("Found Match for "+ image.key);
							LambdaFunctionHandler.SendEmail("Found Match for " + image.key, "ryan.leenay@allyis.com", "TEST EMAIL ", log);
						}
					}
				}
			}
			
		}

		@Override
		public Object call() throws Exception {
			try {
				makeEdgeHashRequest();
			} catch (IOException | URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public void run() {
			try {
				makeEdgeHashRequest();
			} catch (IOException | URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public HashedImage getImage(List<HashedImage> images, String handle) {
		for(HashedImage im : images) {
			if(im.recieptHandler.equals(handle)) {
				return im;
			}
		}
		return null;
	}
	
}
