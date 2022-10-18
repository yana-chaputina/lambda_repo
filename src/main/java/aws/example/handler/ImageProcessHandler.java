package aws.example.handler;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

public class ImageProcessHandler implements RequestHandler<Map<String, Object>, APIGatewayProxyResponseEvent> {
    private static final String SQS_QUEUE_NAME = "sqs-uploads-notification-queue";
    private static final String TOPIC_ARN = "";
    private static final String REGION = "";
    private static final String API = "API";
    private static final String DETAIL_TYPE_PARAM = "detail-type";
    private static final Integer RECEIVE_MESSAGES_TIMEOUT = 3;
    private static final Integer MAX_NUMBER_OF_MESSAGES = 3;

    private final AmazonSNS amazonSNS = AmazonSNSClientBuilder.standard()
            .withRegion(REGION)
            .build();
    private final AmazonSQS amazonSQS = AmazonSQSClientBuilder.standard()
            .withRegion(REGION)
            .build();

    private LambdaLogger log;

    @Override
    public APIGatewayProxyResponseEvent handleRequest(Map<String, Object> input, Context context) {
        Object detail = input.get(DETAIL_TYPE_PARAM);
        String detailType = detail == null ? API : String.valueOf(detail);
        log = context.getLogger();

        int processedMessages = processQueueMessages();

        log.log("Handled Request for ARN = " + TOPIC_ARN
                + "; Request Source = " + detailType
                + "; Function Name = " + context.getFunctionName()
                + "; Processed Messages count = " + processedMessages
                + "; Remaining Time in millis = " + context.getRemainingTimeInMillis());

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("")
                .withIsBase64Encoded(false);
    }

    private int processQueueMessages() {
        GetQueueUrlRequest getQueueRequest = new GetQueueUrlRequest().withQueueName(SQS_QUEUE_NAME);
        String queueUrl = amazonSQS.getQueueUrl(getQueueRequest).getQueueUrl();

        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest()
                .withQueueUrl(queueUrl)
                .withMaxNumberOfMessages(MAX_NUMBER_OF_MESSAGES)
                .withWaitTimeSeconds(RECEIVE_MESSAGES_TIMEOUT);

        List<Message> sqsMessages = IntStream.of(0, MAX_NUMBER_OF_MESSAGES)
                .mapToObj(i -> amazonSQS.receiveMessage(receiveMessageRequest).getMessages())
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        if (sqsMessages.isEmpty()) {
            log.log(" Messages not found. End processing");
            return 0;
        }

        String message = sqsMessages.stream()
                .map(Message::getBody)
                .collect(Collectors.joining("\n=========================\n"));
        log.log(" Result message = \n" + message);

        PublishRequest publishRequest = new PublishRequest()
                .withTopicArn(TOPIC_ARN)
                .withSubject(" Processed SQS Queue Messages")
                .withMessage(message);
        amazonSNS.publish(publishRequest);

        sqsMessages.forEach(sqsMessage -> {
            log.log(" Deleting message id = " + sqsMessage.getMessageId());
            amazonSQS.deleteMessage(queueUrl, sqsMessage.getReceiptHandle());
        });

        return sqsMessages.size();
    }

}