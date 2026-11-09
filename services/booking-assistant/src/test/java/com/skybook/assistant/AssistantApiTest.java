package com.skybook.assistant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.skybook.common.security.Roles;
import com.skybook.common.test.TestJwtIssuer;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * The HTTP contract over a real server, with a fake language model: authentication, validation, per-caller
 * conversation memory, the system rules and the 503 when the model is down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.ai.model.chat=none")
class AssistantApiTest {

    private static final TestJwtIssuer JWT = new TestJwtIssuer();
    private static final String ADA = "Bearer " + JWT.token("ada", Roles.CUSTOMER);
    private static final String BOB = "Bearer " + JWT.token("bob", Roles.CUSTOMER);

    /** Answers with what it saw: the latest user message and how many user messages the prompt contained. */
    static final class FakeChatModel implements ChatModel {

        final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            String last = prompt.getUserMessage().getText();
            if (last.contains("boom")) {
                throw new IllegalStateException("model down");
            }
            long userTurns = prompt.getInstructions().stream()
                    .filter(m -> m.getMessageType() == MessageType.USER).count();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    last.contains("silent") ? " " : "echo: " + last + " (turns " + userTurns + ")"))));
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        FakeChatModel chatModel() {
            return new FakeChatModel();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return JWT.decoder();
        }
    }

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private FakeChatModel model;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        model.prompts.clear();
    }

    private String send(String token, String body) {
        return given().header("Authorization", token).contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(200)
                .extract().path("conversationId");
    }

    @Test
    void remembersAConversationForItsOwnerOnly() {
        String conversation = send(ADA, "{\"message\": \"Flights to London?\"}");
        assertThat(conversation).matches("[0-9a-f-]{36}");

        given().header("Authorization", ADA).contentType(ContentType.JSON)
                .body("{\"conversationId\": \"" + conversation + "\", \"message\": \"And on Friday?\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(200)
                .body("conversationId", equalTo(conversation))
                .body("reply", equalTo("echo: And on Friday? (turns 2)"));

        // Bob reusing Ada's conversation id starts from an empty history.
        given().header("Authorization", BOB).contentType(ContentType.JSON)
                .body("{\"conversationId\": \"" + conversation + "\", \"message\": \"What did she ask?\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(200)
                .body("reply", equalTo("echo: What did she ask? (turns 1)"));
    }

    @Test
    void sendsTheSystemRulesWithTodaysDate() {
        send(ADA, "{\"message\": \"Can you cancel my booking?\"}");

        String system = model.prompts.getFirst().getSystemMessage().getText();
        assertThat(system).contains("You cannot book, pay, change, cancel or refund anything")
                .contains("100% at least 7 days before departure")
                .containsPattern("Today is \\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void requiresAuthenticationAndValidInput() {
        given().contentType(ContentType.JSON).body("{\"message\": \"hi\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(401);

        given().header("Authorization", ADA).contentType(ContentType.JSON).body("{\"message\": \"\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(400)
                .contentType(matchesPattern("application/problem\\+json.*"));

        given().header("Authorization", ADA).contentType(ContentType.JSON)
                .body("{\"message\": \"" + "x".repeat(1001) + "\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(400);
    }

    @Test
    void reportsAnUnavailableModelAsAProblemAndBlankAnswersWithAFallback() {
        given().header("Authorization", ADA).contentType(ContentType.JSON).body("{\"message\": \"boom\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(503)
                .body("code", equalTo("ASSISTANT_UNAVAILABLE"));

        given().header("Authorization", ADA).contentType(ContentType.JSON).body("{\"message\": \"silent\"}")
                .when().post("/api/v1/assistant/messages")
                .then().statusCode(200)
                .body("reply", equalTo("Sorry, I could not come up with an answer. Could you rephrase the question?"));
    }
}
