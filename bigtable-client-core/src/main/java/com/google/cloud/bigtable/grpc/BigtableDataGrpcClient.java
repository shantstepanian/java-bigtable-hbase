/*
 * Copyright 2015 Google Inc. All Rights Reserved.
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
package com.google.cloud.bigtable.grpc;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.bigtable.v1.BigtableServiceGrpc;
import com.google.bigtable.v1.CheckAndMutateRowRequest;
import com.google.bigtable.v1.CheckAndMutateRowResponse;
import com.google.bigtable.v1.MutateRowRequest;
import com.google.bigtable.v1.MutateRowsRequest;
import com.google.bigtable.v1.MutateRowsRequest.Entry;
import com.google.bigtable.v1.MutateRowsResponse;
import com.google.bigtable.v1.Mutation;
import com.google.bigtable.v1.ReadModifyWriteRowRequest;
import com.google.bigtable.v1.ReadRowsRequest;
import com.google.bigtable.v1.ReadRowsResponse;
import com.google.bigtable.v1.Row;
import com.google.bigtable.v1.SampleRowKeysRequest;
import com.google.bigtable.v1.SampleRowKeysResponse;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.config.RetryOptions;
import com.google.cloud.bigtable.grpc.async.BigtableAsyncUtilities;
import com.google.cloud.bigtable.grpc.async.RetryingRpcFunction;
import com.google.cloud.bigtable.grpc.async.BigtableAsyncRpc;
import com.google.cloud.bigtable.grpc.io.CancellationToken;
import com.google.cloud.bigtable.grpc.io.ChannelPool;
import com.google.cloud.bigtable.grpc.scanner.BigtableResultScannerFactory;
import com.google.cloud.bigtable.grpc.scanner.ReadRowsResponseListener;
import com.google.cloud.bigtable.grpc.scanner.ResponseQueueReader;
import com.google.cloud.bigtable.grpc.scanner.ResultScanner;
import com.google.cloud.bigtable.grpc.scanner.ResumingStreamingResultScanner;
import com.google.cloud.bigtable.grpc.scanner.RowMerger;
import com.google.cloud.bigtable.grpc.scanner.StreamingBigtableResultScanner;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Empty;
import com.google.protobuf.ServiceException;

/**
 * A gRPC client to access the v1 Bigtable service.
 */
public class BigtableDataGrpcClient implements BigtableDataClient {

  private static final Logger LOG = new Logger(BigtableDataGrpcClient.class);

  // Retryable Predicates

  @VisibleForTesting
  public static final Predicate<MutateRowRequest> IS_RETRYABLE_MUTATION =
      new Predicate<MutateRowRequest>() {
        @Override
        public boolean apply(MutateRowRequest mutateRowRequest) {
          return mutateRowRequest != null
              && allCellsHaveTimestamps(mutateRowRequest.getMutationsList());
        }
      };

  @VisibleForTesting
  public static final Predicate<MutateRowsRequest> ARE_RETRYABLE_MUTATIONS =
      new Predicate<MutateRowsRequest>() {
        @Override
        public boolean apply(MutateRowsRequest mutateRowsRequest) {
          if (mutateRowsRequest == null) {
            return false;
          }
          for (Entry entry : mutateRowsRequest.getEntriesList()) {
            if (!allCellsHaveTimestamps(entry.getMutationsList())) {
              return false;
            }
          }
          return true;
        }
      };

  @VisibleForTesting
  public static final Predicate<CheckAndMutateRowRequest> IS_RETRYABLE_CHECK_AND_MUTATE =
      new Predicate<CheckAndMutateRowRequest>() {
        @Override
        public boolean apply(CheckAndMutateRowRequest checkAndMutateRowRequest) {
          return checkAndMutateRowRequest != null
              && allCellsHaveTimestamps(checkAndMutateRowRequest.getTrueMutationsList())
              && allCellsHaveTimestamps(checkAndMutateRowRequest.getFalseMutationsList());
        }
      };

  private static final boolean allCellsHaveTimestamps(Iterable<Mutation> mutations) {
    for (Mutation mut : mutations) {
      if (mut.getSetCell().getTimestampMicros() <= 0) {
        return false;
      }
    }
    return true;
  }

  // Streaming API transformers

  private static final Function<List<SampleRowKeysResponse>, List<SampleRowKeysResponse>> IMMUTABLE_LIST_TRANSFORMER =
      new Function<List<SampleRowKeysResponse>, List<SampleRowKeysResponse>>() {
        @Override
        public List<SampleRowKeysResponse> apply(List<SampleRowKeysResponse> list) {
          return ImmutableList.copyOf(list);
        }
      };

  private static Function<List<ReadRowsResponse>, List<Row>> ROW_TRANSFORMER =
      new Function<List<ReadRowsResponse>, List<Row>>() {
        @Override
        public List<Row> apply(List<ReadRowsResponse> responses) {
          List<Row> result = new ArrayList<>();
          Iterator<ReadRowsResponse> responseIterator = responses.iterator();
          while (responseIterator.hasNext()) {
            result.add(RowMerger.readNextRow(responseIterator));
          }
          return result;
        }
      };

  // Member variables

  private final ChannelPool channelPool;
  private final ScheduledExecutorService retryExecutorService;
  private final RetryOptions retryOptions;
  private final BigtableOptions bigtableOptions;
  private final BigtableResultScannerFactory streamingScannerFactory =
      new BigtableResultScannerFactory() {
        @Override
        public ResultScanner<Row> createScanner(ReadRowsRequest request) {
          return streamRows(request);
        }
      };

  private final BigtableAsyncUtilities asyncUtilities;

  private final BigtableAsyncRpc<SampleRowKeysRequest, List<SampleRowKeysResponse>> sampleRowKeysAsync;
  private final BigtableAsyncRpc<ReadRowsRequest, List<Row>> readRowsAsync;

  private final BigtableAsyncRpc<MutateRowRequest, Empty> mutateRowRpc;
  private final BigtableAsyncRpc<MutateRowsRequest, MutateRowsResponse> mutateRowsRpc;
  private final BigtableAsyncRpc<CheckAndMutateRowRequest, CheckAndMutateRowResponse> checkAndMutateRpc;
  private final BigtableAsyncRpc<ReadModifyWriteRowRequest, Row> readWriteModifyRpc;

  public BigtableDataGrpcClient(ChannelPool channelPool,
      ScheduledExecutorService retryExecutorService, BigtableOptions bigtableOptions) {
    this(channelPool, retryExecutorService, bigtableOptions,
        new BigtableAsyncUtilities.Default(channelPool));
  }

  @VisibleForTesting
  BigtableDataGrpcClient(
      ChannelPool channelPool,
      ScheduledExecutorService retryExecutorService,
      BigtableOptions bigtableOptions,
      BigtableAsyncUtilities asyncUtilities) {
    this.channelPool = channelPool;
    this.retryExecutorService = retryExecutorService;
    this.bigtableOptions = bigtableOptions;
    this.retryOptions = bigtableOptions.getRetryOptions();
    this.asyncUtilities = asyncUtilities;

    this.sampleRowKeysAsync =
        asyncUtilities.createStreamingAsyncRpc(
            BigtableServiceGrpc.METHOD_SAMPLE_ROW_KEYS, IMMUTABLE_LIST_TRANSFORMER);
    this.readRowsAsync =
        asyncUtilities.createStreamingAsyncRpc(
            BigtableServiceGrpc.METHOD_READ_ROWS, ROW_TRANSFORMER);
    this.mutateRowRpc =
        asyncUtilities.createAsyncUnaryRpc(
            BigtableServiceGrpc.METHOD_MUTATE_ROW, IS_RETRYABLE_MUTATION);
    this.mutateRowsRpc =
        asyncUtilities.createAsyncUnaryRpc(
            BigtableServiceGrpc.METHOD_MUTATE_ROWS, ARE_RETRYABLE_MUTATIONS);
    this.checkAndMutateRpc =
        asyncUtilities.createAsyncUnaryRpc(
            BigtableServiceGrpc.METHOD_CHECK_AND_MUTATE_ROW, IS_RETRYABLE_CHECK_AND_MUTATE);
    this.readWriteModifyRpc =
        asyncUtilities.createAsyncUnaryRpc(
            BigtableServiceGrpc.METHOD_READ_MODIFY_WRITE_ROW,
            Predicates.<ReadModifyWriteRowRequest>alwaysFalse());
  }

  @Override
  public Empty mutateRow(MutateRowRequest request) throws ServiceException {
    return performBlockingRpc(request, mutateRowRpc);
  }

  @Override
  public ListenableFuture<Empty> mutateRowAsync(MutateRowRequest request) {
    return performAsyncRpc(request, mutateRowRpc);
  }

  @Override
  public MutateRowsResponse mutateRows(MutateRowsRequest request) throws ServiceException {
    return performBlockingRpc(request, mutateRowsRpc);
  }

  @Override
  public ListenableFuture<MutateRowsResponse> mutateRowsAsync(MutateRowsRequest request) {
    return performAsyncRpc(request, mutateRowsRpc);
  }

  @Override
  public CheckAndMutateRowResponse checkAndMutateRow(CheckAndMutateRowRequest request)
      throws ServiceException {
    return performBlockingRpc(request, checkAndMutateRpc);
  }

  @Override
  public ListenableFuture<CheckAndMutateRowResponse> checkAndMutateRowAsync(
      CheckAndMutateRowRequest request) {
    return performAsyncRpc(request, checkAndMutateRpc);
  }

  @Override
  public Row readModifyWriteRow(ReadModifyWriteRowRequest request) {
    return performBlockingRpc(request, readWriteModifyRpc);
  }

  @Override
  public ListenableFuture<Row> readModifyWriteRowAsync(ReadModifyWriteRowRequest request) {
    return performAsyncRpc(request, readWriteModifyRpc);
  }

  @Override
  public ImmutableList<SampleRowKeysResponse> sampleRowKeys(SampleRowKeysRequest request) {
    return ImmutableList.copyOf(performBlockingRpc(request, sampleRowKeysAsync));
  }

  @Override
  public ListenableFuture<List<SampleRowKeysResponse>> sampleRowKeysAsync(
      SampleRowKeysRequest request) {
    return performAsyncRpc(request, sampleRowKeysAsync);
  }

  @Override
  public ListenableFuture<List<Row>> readRowsAsync(ReadRowsRequest request) {
    return performAsyncRpc(request, readRowsAsync);
  }

  // Helper methods 

  private <ReqT, RespT> ListenableFuture<RespT> performAsyncRpc(
      ReqT request, BigtableAsyncRpc<ReqT, RespT> rpc) {
    expandPoolIfNecessary(this.bigtableOptions.getChannelCount());
    return performRetryingAsyncRpc(request, rpc, null);
  }

  private <ReqT, RespT> RespT performBlockingRpc(ReqT request, BigtableAsyncRpc<ReqT, RespT> rpc) {
    CancellationToken token = new CancellationToken();
    try {
      return performRetryingAsyncRpc(request, rpc, token).get();
    } catch (InterruptedException e) {
      token.cancel();
      Thread.currentThread().interrupt();
      throw Status.CANCELLED.withCause(e).asRuntimeException();
    } catch (ExecutionException e) {
      token.cancel();
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }

  private <ReqT, RespT> ListenableFuture<RespT> performRetryingAsyncRpc(
      ReqT request, BigtableAsyncRpc<ReqT, RespT> rpc, CancellationToken cancellationToken) {
    return new RetryingRpcFunction<>(
            retryOptions, request, rpc, retryExecutorService, cancellationToken)
        .callRpcWithRetry();
  }

  // Scanner methods
  
  @Override
  public ResultScanner<Row> readRows(ReadRowsRequest request) {
    // Delegate all resumable operations to the scanner. It will request a non-resumable
    // scanner during operation.
    if (retryOptions.enableRetries()) {
      return new ResumingStreamingResultScanner(retryOptions, request, streamingScannerFactory);
    } else {
      return streamRows(request);
    }
  }

  private ResultScanner<Row> streamRows(ReadRowsRequest request) {
    expandPoolIfNecessary(this.bigtableOptions.getChannelCount());
    boolean isGet = request.getTargetCase() == ReadRowsRequest.TargetCase.ROW_KEY;

    int streamingBufferSize;
    int batchRequestSize;

    if (isGet) {
      // Batch request size is more performant with a value of 1 for single row gets, while a higher
      // number is more performant for scanning
      batchRequestSize = 1;
      streamingBufferSize = 10;
    } else {
      batchRequestSize = retryOptions.getStreamingBatchSize();
      streamingBufferSize = retryOptions.getStreamingBufferSize();
    }

    ClientCall<ReadRowsRequest, ReadRowsResponse> readRowsCall =
        channelPool.newCall(BigtableServiceGrpc.METHOD_READ_ROWS, CallOptions.DEFAULT);

    int timeout = retryOptions.getReadPartialRowTimeoutMillis();
    AtomicInteger outstandingRequestCount = new AtomicInteger(batchRequestSize);

    ResponseQueueReader responseQueueReader =
        new ResponseQueueReader(timeout, streamingBufferSize, outstandingRequestCount,
            batchRequestSize, readRowsCall);
    ReadRowsResponseListener listener =
        new ReadRowsResponseListener(responseQueueReader, outstandingRequestCount);
    asyncUtilities.asyncServerStreamingCall(readRowsCall, request, listener);

    CancellationToken cancellationToken = createCancellationToken(readRowsCall);
    return new StreamingBigtableResultScanner(responseQueueReader, cancellationToken);
  }

  private CancellationToken createCancellationToken(final ClientCall<ReadRowsRequest, ReadRowsResponse> readRowsCall) {
    // If the scanner is closed before we're done streaming, we want to cancel the RPC.
    CancellationToken cancellationToken = new CancellationToken();
    cancellationToken.addListener(new Runnable() {
      @Override
      public void run() {
        readRowsCall.cancel();
      }
    }, MoreExecutors.directExecutor());
    return cancellationToken;
  }

  private void expandPoolIfNecessary(int channelCount) {
    try {
      this.channelPool.ensureChannelCount(channelCount);
    } catch (IOException e) {
      LOG.info("Could not expand the channel pool.", e);
    }
  }
}
