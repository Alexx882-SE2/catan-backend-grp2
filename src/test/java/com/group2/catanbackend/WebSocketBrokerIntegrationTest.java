package com.group2.catanbackend;

import com.group2.catanbackend.dto.JoinRequestDto;
import com.group2.catanbackend.service.TokenService;
import com.group2.catanbackend.websocket.StompFrameHandlerClientImpl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketBrokerIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;

    private final String WEBSOCKET_URI = "ws://localhost:%d/catan";
    private final String WEBSOCKET_TOPIC_PREFIX = "/topic/game/";

    /**
     * Message Queue
     */
    BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    @Test
    public void testProtocolUpgradeAllowedIfValidToken() throws Exception{
        JoinRequestDto dto = new JoinRequestDto("player", "game");
        String token = UUID.randomUUID().toString();
        tokenService.pushToken(token, dto);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("authorization", token);

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        StompSession session = stompClient.connectAsync(String.format(WEBSOCKET_URI, port), headers, new StompSessionHandlerAdapter() {
        }).get(1, TimeUnit.SECONDS);

        Assertions.assertTrue(session.isConnected());
    }

    @Test
    public void testProtocolUpgradeNotAllowedIfTokenInvalid(){
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("authorization", "invalidToken");

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        Assertions.assertThrows(Exception.class, ()->{
            stompClient.connectAsync(String.format(WEBSOCKET_URI, port),
                    headers,
                    new StompSessionHandlerAdapter() {
        })
                    .get(1, TimeUnit.SECONDS);});

    }

    @Test
    public void testProtocolUpgradeNotAllowedIfNoTokenPresent(){
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        Assertions.assertThrows(Exception.class, ()->{
            stompClient.connectAsync(String.format(WEBSOCKET_URI, port),
                            new StompSessionHandlerAdapter() {
                            })
                    .get(1, TimeUnit.SECONDS);});

    }

    @Test
    public void testSubscriptionAllowedIfPlayerIsInGame() throws Exception{
        String gameID = "GameID";
        String token = UUID.randomUUID().toString();

        StompSession session = initValidSession("Player", gameID, token);

        String topic = WEBSOCKET_TOPIC_PREFIX + gameID + "/messages";
        session.subscribe(topic, new StompFrameHandlerClientImpl(messages));
        String message = "hello";
        session.send(topic, message);
        assertThat(messages.poll(1, TimeUnit.SECONDS)).isEqualTo(message);
    }

    @Test
    public void testSubscriptionNotAllowedIfPlayerIsNotInGame() throws Exception{
        String gameID = "GameID";

        String token = UUID.randomUUID().toString();
        StompSession session = initValidSession("Player", gameID, token);

        String topic = WEBSOCKET_TOPIC_PREFIX + "notGameID" + "/messages";
        session.subscribe(topic, new StompFrameHandlerClientImpl(messages));
        String message = "hello";
        session.send(topic, message);
        assertThat(messages.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    public void testUserCanReceiveMessagesOnPrivateChannel() throws Exception{
        String token = tokenService.generateToken();
        StompSession session = initValidSession("playerName", "gameID", token);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        session.subscribe("/user/queue/messages/", new StompFrameHandlerClientImpl(messages));

        Thread.sleep(1000); //Test failed because message was sent before subscription was processed

        simpMessagingTemplate.convertAndSendToUser(token, "/queue/messages/", "Hello");
        assertThat(messages.poll(5, TimeUnit.SECONDS)).isEqualTo("Hello");
    }

    @Test
    public void testUserACannotReadMessagesFromUserB() throws Exception{
        String tokenA = tokenService.generateToken();
        StompSession sessionA = initValidSession("userA", "gameID", tokenA);
        BlockingQueue<String> messagesA = new LinkedBlockingQueue<>();

        String tokenB = tokenService.generateToken();
        StompSession sessionB = initValidSession("userB", "gameID", tokenB);
        BlockingQueue<String> messagesB = new LinkedBlockingQueue<>();

        sessionA.subscribe("/user/queue/messages/", new StompFrameHandlerClientImpl(messagesA));
        sessionB.subscribe("/user/queue/messages/", new StompFrameHandlerClientImpl(messagesB));

        Thread.sleep(1000); //Test failed because message was sent before subscription was processed

        simpMessagingTemplate.convertAndSendToUser(tokenB, "/queue/messages/", "Hello");

        assertThat(messagesA.poll(5, TimeUnit.SECONDS)).isNull();
        assertThat(messagesB.poll(5, TimeUnit.SECONDS)).isEqualTo("Hello");
    }

    public StompSession initValidSession(String playerName, String gameID, String token) throws Exception{
        JoinRequestDto dto = new JoinRequestDto(playerName, gameID);
        tokenService.pushToken(token, dto);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("authorization", token);

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
        StompSession session = stompClient.connectAsync(String.format(WEBSOCKET_URI, port), headers, new StompSessionHandlerAdapter() {
        }).get(2, TimeUnit.SECONDS);
        return session;
    }
}
