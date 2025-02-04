package io.github.vertxchina;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Leibniz on 2022/02/25 10:27 PM
 */
@ExtendWith(VertxExtension.class)
public class TcpServerVerticleTest {

  @Test
  void singleClientSendMessageTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> singleClientSendMessageTest() Start");
    int port = 9527;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
      .onSuccess(did -> createClients(vertx, port, 1)
        .onSuccess(ar -> sendMessages(vertx, ar).get(0)
          .onSuccess(msgList -> {
            assert msgList.size() == 0; //登陆后响应消息不包含有message域，不统计在内，自身发出的消息不再发回来
            testCtx.completeNow();
          })
          .onFailure(testCtx::failNow))
        .onFailure(testCtx::failNow))
      .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> singleClientSendMessageTest() End");
  }

  @Test
  @SuppressWarnings("rawtypes")
  void multiClientSendMessageMutuallyTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> multiClientSendMessageMutuallyTest() Start");
    int port = 6666;
    int clientNum = 3;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
      .onSuccess(did -> createClients(vertx, port, clientNum)
        .onSuccess(ar -> CompositeFuture.all(cast(sendMessages(vertx, ar)))
          .onSuccess(cf -> cf
            .onSuccess(closed -> {
              for (Object o : closed.result().list()) {
                assert ((List) o).size() == clientNum-1; //N条其他Clients发出的消息，仅保留有消息（message字段）的消息，登陆后的响应和退出消息不保留，另外自身发送的消息不再返回
              }
              testCtx.completeNow();
            })
            .onFailure(testCtx::failNow))
          .onFailure(testCtx::failNow))
        .onFailure(testCtx::failNow))
      .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> multiClientSendMessageMutuallyTest() End");
  }

  @Test
  void singleClientSendMessageErrorTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> singleClientSendMessageErrorTest() Start");
    int port = 9527;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
        .onSuccess(did -> createClients(vertx, port, 1)
            .onSuccess(ar -> sendErrorMessages(vertx, ar).get(0)
                .onSuccess(msgList -> {
                  assert msgList.size() == 1; //发送错误消息应返回解析错误消息
                  var stacktrace = msgList.get(0).getString("message");
                  System.out.println(stacktrace);
                  testCtx.completeNow();
                })
                .onFailure(testCtx::failNow))
            .onFailure(testCtx::failNow))
        .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> singleClientSendMessageErrorTest() End");
  }

  private List<Future<List<JsonObject>>> sendErrorMessages(Vertx vertx, AsyncResult<CompositeFuture> ar) {
    List<Future<List<JsonObject>>> closeFutures = new ArrayList<>();
    for (Object o : ar.result().list()) {
      if (o instanceof TreeNewBeeClient client) {
        var socket = client.socket;
        socket.write("fjdslkjlfa\r\n");
        Promise<List<JsonObject>> promise = Promise.promise();
        closeFutures.add(promise.future());
        socket.closeHandler(v -> {
          System.out.println("Client " + socket + " closed");
          promise.complete(client.msgList);
        });
        vertx.setTimer(1000L, tid -> socket.close());
      }
    }
    return closeFutures;
  }

  private List<Future<List<JsonObject>>> sendMessages(Vertx vertx, AsyncResult<CompositeFuture> ar) {
    List<Future<List<JsonObject>>> closeFutures = new ArrayList<>();
    for (Object o : ar.result().list()) {
      if (o instanceof TreeNewBeeClient client) {
        var clientId = client.id;
        var socket = client.socket;
        socket.write(new JsonObject()
          .put("time", System.currentTimeMillis())
          .put("message", "Hello gays! I'm client " + clientId)
          .put("fromClientId", clientId).toString() + "\r\n");
        Promise<List<JsonObject>> promise = Promise.promise();
        closeFutures.add(promise.future());
        socket.closeHandler(v -> {
          System.out.println("Client " + socket + " closed");
          promise.complete(client.msgList);
        });
        vertx.setTimer(1000L, tid -> socket.close());
      }
    }
    return closeFutures;
  }

  @SuppressWarnings("rawtypes")
  private CompositeFuture createClients(Vertx vertx, int port, int num) {
    List<Future> createClientFutures = new ArrayList<>();
    for (int i = 0; i < num; i++) {
      var clientId = i;
      createClientFutures.add(
        vertx.createNetClient()
          .connect(port, "localhost")
          .map(s -> new TreeNewBeeClient(s, clientId, new ArrayList<>()))
          .onSuccess(client -> {
            System.out.println("Client " + clientId + " Connected!");
            client.socket.handler(client::receiveMsg);
          })
          .onFailure(e -> System.out.println("Failed to connect: " + e.getMessage()))
      );
    }
    return CompositeFuture.all(createClientFutures);
  }

  record TreeNewBeeClient(NetSocket socket, int id, List<JsonObject> msgList) {
    void receiveMsg(Buffer buffer) {
      try {
        String[] jsonStrings = buffer.toString().split("\r\n");
        for(String jsonString:jsonStrings){
          JsonObject msg = new JsonObject(jsonString.trim());
          System.out.println("Client " + id + " Received message: " + msg);
          if(msg.containsKey("message")) {
            msgList.add(msg);
          }
        }
      } catch (Exception e) {
        System.out.println("Client " + id + " parse message err: " + e.getMessage() + "original message:" + buffer.toString());
      }
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> T cast(Object obj) {
    return (T) obj;
  }
}