/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.wallet;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.examples.wallet.account.AccountGrpc;
import io.grpc.examples.wallet.account.GetUserInfoRequest;
import io.grpc.examples.wallet.account.GetUserInfoResponse;
import io.grpc.examples.wallet.account.MembershipType;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.logging.Logger;

/** Account server for the gRPC Wallet example. */
public class AccountServer {
  private static final Logger logger = Logger.getLogger(AccountServer.class.getName());

  private Server server;

  private int port = 18883;
  private String hostnameSuffix = "";
  private String observabilityProject = "";

  void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All flags must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("port".equals(key)) {
        port = Integer.parseInt(value);
      } else if ("hostname_suffix".equals(key)) {
        hostnameSuffix = value;
      } else if ("observability_project".equals(key)) {
        observabilityProject = value;
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      AccountServer s = new AccountServer();
      System.out.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --port=PORT            The port to listen on. Default "
              + s.port
              + "\n  --hostname_suffix=STR  Suffix to append to hostname in response header. "
              + "Default \""
              + s.hostnameSuffix
              + "\""
              + "\n  --observability_project=STR GCP project. If set, metrics and traces will be "
              + "sent to Stackdriver. Default \"" + s.observabilityProject + "\"");
      System.exit(1);
    }
  }

  private void start() throws IOException {
    if (!observabilityProject.isEmpty()) {
      Observability.registerExporters(observabilityProject);
    }
    HealthStatusManager health = new HealthStatusManager();
    server =
        ServerBuilder.forPort(port)
            .addService(
                ServerInterceptors.intercept(
                    new AccountImpl(), new WalletInterceptors.HostnameInterceptor()))
            .addService(ProtoReflectionService.newInstance())
            .addService(health.getHealthService())
            .build()
            .start();
    health.setStatus("", ServingStatus.SERVING);
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                  AccountServer.this.stop();
                } catch (InterruptedException e) {
                  e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
              }
            });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, SECONDS);
    }
  }

  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    final AccountServer server = new AccountServer();
    server.parseArgs(args);
    server.start();
    server.blockUntilShutdown();
  }

  private static class AccountImpl extends AccountGrpc.AccountImplBase {
    @Override
    public void getUserInfo(
        GetUserInfoRequest req, StreamObserver<GetUserInfoResponse> responseObserver) {
      String token = req.getToken();
      GetUserInfoResponse.Builder response = GetUserInfoResponse.newBuilder();
      if (Client.ALICE_TOKEN.equals(token)) {
        response.setName("Alice").setMembership(MembershipType.PREMIUM);
      } else if (Client.BOB_TOKEN.equals(token)) {
        response.setName("Bob").setMembership(MembershipType.NORMAL);
      } else {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("Unknown token").asRuntimeException());
        return;
      }
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    }
  }
}
