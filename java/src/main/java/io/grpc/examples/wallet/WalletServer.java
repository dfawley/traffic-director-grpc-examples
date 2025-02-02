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

import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.wallet.account.AccountGrpc;
import io.grpc.examples.wallet.account.GetUserInfoRequest;
import io.grpc.examples.wallet.account.GetUserInfoResponse;
import io.grpc.examples.wallet.account.MembershipType;
import io.grpc.examples.wallet.stats.PriceRequest;
import io.grpc.examples.wallet.stats.PriceResponse;
import io.grpc.examples.wallet.stats.StatsGrpc;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.HealthStatusManager;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Wallet server for the gRPC Wallet example. */
public class WalletServer {
  private static final Logger logger = Logger.getLogger(WalletServer.class.getName());

  private Server server;
  private int port = 18881;
  private String accountServer = "localhost:18883";
  private String statsServer = "localhost:18882";
  private String hostnameSuffix = "";
  private String observabilityProject = "";
  private boolean v1Behavior;

  private ManagedChannel accountChannel;
  private ManagedChannel statsChannel;

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
      } else if ("account_server".equals(key)) {
        accountServer = value;
      } else if ("stats_server".equals(key)) {
        statsServer = value;
      } else if ("hostname_suffix".equals(key)) {
        hostnameSuffix = value;
      } else if ("observability_project".equals(key)) {
        observabilityProject = value;
      } else if ("v1_behavior".equals(key)) {
        v1Behavior = Boolean.parseBoolean(value);
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      WalletServer s = new WalletServer();
      System.out.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --port=PORT                The port to listen on. Default "
              + s.port
              + "\n  --account_server=HOST      Address of the account server. Default "
              + s.accountServer
              + "\n  --stats_server=HOST        Address of the stats server. Default "
              + s.statsServer
              + "\n  --hostname_suffix=STR      Suffix to append to hostname in response header. "
              + "Default \""
              + s.hostnameSuffix
              + "\""
              + "\n  --observability_project=STR GCP project. If set, metrics and traces will be "
              + "sent to Stackdriver. Default \"" + s.observabilityProject + "\""
              + "\n  --v1_behavior=true|false   If true, only aggregate balance is reported. "
              + "Default "
              + s.v1Behavior);
      System.exit(1);
    }
  }

  private void start() throws IOException {
    if (!observabilityProject.isEmpty()) {
      Observability.registerExporters(observabilityProject);
    }
    accountChannel = ManagedChannelBuilder.forTarget(accountServer).usePlaintext().build();
    statsChannel = ManagedChannelBuilder.forTarget(statsServer).usePlaintext().build();
    HealthStatusManager health = new HealthStatusManager();
    server =
        ServerBuilder.forPort(port)
            .addService(
                ServerInterceptors.intercept(
                    new WalletImpl(accountChannel, statsChannel, v1Behavior),
                    new WalletInterceptors.HostnameInterceptor(),
                    new WalletInterceptors.AuthInterceptor()))
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
                  WalletServer.this.stop();
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
    if (accountChannel != null) {
      accountChannel.shutdownNow().awaitTermination(5, SECONDS);
    }
    if (statsChannel != null) {
      statsChannel.shutdownNow().awaitTermination(5, SECONDS);
    }
  }

  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    final WalletServer server = new WalletServer();
    server.parseArgs(args);
    server.start();
    server.blockUntilShutdown();
  }

  private static class WalletImpl extends WalletGrpc.WalletImplBase {
    private final AccountGrpc.AccountBlockingStub accountBlockingStub;
    private final StatsGrpc.StatsBlockingStub statsBlockingStub;
    private final boolean v1Behavior;
    private final ImmutableMap<String, Long> alicesWallet =
        ImmutableMap.<String, Long>builder().put("cd0aa985", 314L).put("454349e4", 159L).build();
    private final ImmutableMap<String, Long> bobsWallet =
        ImmutableMap.<String, Long>builder().put("148de9c5", 271L).put("2e7d2c03", 828L).build();

    private WalletImpl(
        ManagedChannel accountChannel, ManagedChannel statsChannel, boolean v1Behavior) {
      this.accountBlockingStub = AccountGrpc.newBlockingStub(accountChannel);
      this.statsBlockingStub = StatsGrpc.newBlockingStub(statsChannel);
      this.v1Behavior = v1Behavior;
    }

    private ImmutableMap<String, Long> validateMembershipAndGetWallet(
        String token, String membership) {
      GetUserInfoResponse userInfo;
      try {
        userInfo =
            accountBlockingStub.getUserInfo(
                GetUserInfoRequest.newBuilder().setToken(token).build());
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARNING, "Account RPC failed: {0}", e.getStatus());
        throw Status.INTERNAL
            .withDescription("Failed to connect to account server " + e.getMessage())
            .asRuntimeException();
      }
      if ("premium".equals(membership) && userInfo.getMembership() != MembershipType.PREMIUM) {
        throw Status.UNAUTHENTICATED
            .withDescription("Token does not belong to a premium member")
            .asRuntimeException();
      }
      if ("Alice".equals(userInfo.getName())) {
        return alicesWallet;
      } else if ("Bob".equals(userInfo.getName())) {
        return bobsWallet;
      } else {
        throw Status.NOT_FOUND.withDescription("User not found").asRuntimeException();
      }
    }

    private BalanceResponse buildBalanceResponse(
        Map<String, Long> wallet, long price, boolean includeBalancePerAddress) {
      BalanceResponse.Builder response = BalanceResponse.newBuilder();
      long totalBalance = 0;
      for (Map.Entry<String, Long> entry : wallet.entrySet()) {
        long balance = entry.getValue() * price;
        totalBalance += balance;
        if (!v1Behavior && includeBalancePerAddress) {
          response.addAddresses(
              BalancePerAddress.newBuilder().setAddress(entry.getKey()).setBalance(balance));
        }
      }
      return response.setBalance(totalBalance).build();
    }

    @Override
    public void watchBalance(
        BalanceRequest request, StreamObserver<BalanceResponse> responseObserver) {
      String token = WalletInterceptors.TOKEN_KEY.get();
      String membership = WalletInterceptors.MEMBERSHIP_KEY.get();

      Map<String, Long> wallet;
      try {
        wallet = validateMembershipAndGetWallet(token, membership);
      } catch (StatusRuntimeException e) {
        responseObserver.onError(e);
        return;
      }

      Metadata headers = new Metadata();
      headers.put(WalletInterceptors.TOKEN_MD_KEY, token);
      headers.put(WalletInterceptors.MEMBERSHIP_MD_KEY, membership);

      StatsGrpc.StatsBlockingStub stubWithHeaders =
          MetadataUtils.attachHeaders(statsBlockingStub, headers);
      try {
        Iterator<PriceResponse> prices =
            stubWithHeaders.watchPrice(PriceRequest.getDefaultInstance());
        while (prices.hasNext()) {
          responseObserver.onNext(
              buildBalanceResponse(
                  wallet, prices.next().getPrice(), request.getIncludeBalancePerAddress()));
        }
      } catch (StatusRuntimeException e) {
        responseObserver.onError(
            Status.INTERNAL
                .withDescription("RPC to stats server failed: " + e.getMessage())
                .asRuntimeException());
        return;
      }
    }

    @Override
    public void fetchBalance(
        BalanceRequest request, StreamObserver<BalanceResponse> responseObserver) {
      String token = WalletInterceptors.TOKEN_KEY.get();
      String membership = WalletInterceptors.MEMBERSHIP_KEY.get();

      Map<String, Long> wallet;
      try {
        wallet = validateMembershipAndGetWallet(token, membership);
      } catch (StatusRuntimeException e) {
        responseObserver.onError(e);
        return;
      }

      Metadata headers = new Metadata();
      headers.put(WalletInterceptors.TOKEN_MD_KEY, token);
      headers.put(WalletInterceptors.MEMBERSHIP_MD_KEY, membership);

      StatsGrpc.StatsBlockingStub stubWithHeaders =
          MetadataUtils.attachHeaders(statsBlockingStub, headers);

      try {
        PriceResponse response = stubWithHeaders.fetchPrice(PriceRequest.getDefaultInstance());
        responseObserver.onNext(
            buildBalanceResponse(
                wallet, response.getPrice(), request.getIncludeBalancePerAddress()));
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARNING, "Stats RPC failed: {0}", e.getStatus());
        responseObserver.onError(
            Status.INTERNAL
                .withDescription("RPC to stats server failed: " + e.getMessage())
                .asRuntimeException());
        return;
      }
    }
  }
}
