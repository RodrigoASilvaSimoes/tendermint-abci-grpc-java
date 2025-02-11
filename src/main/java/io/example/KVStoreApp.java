package io.example;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import jetbrains.exodus.ArrayByteIterable;
import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.env.Environment;
import jetbrains.exodus.env.Store;
import jetbrains.exodus.env.Cursor;
import jetbrains.exodus.env.StoreConfig;
import jetbrains.exodus.env.Transaction;
import tendermint.abci.ABCIApplicationGrpc;
import tendermint.abci.Types.*;
import io.example.Pair;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

class KVStoreApp extends ABCIApplicationGrpc.ABCIApplicationImplBase {
    private Environment env;
    private Transaction txn = null;
    private Store store = null;
    private Transaction txnSnapshot = null;
    private Cursor storeCursor = null;

    KVStoreApp(Environment env) {
        this.env = env;
    }

    @Override
    public void echo(RequestEcho req, StreamObserver<ResponseEcho> responseObserver) {
        var resp = ResponseEcho.newBuilder().build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void info(RequestInfo req, StreamObserver<ResponseInfo> responseObserver) {
        var resp = ResponseInfo.newBuilder().build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void setOption(RequestSetOption req, StreamObserver<ResponseSetOption> responseObserver) {
        var resp = ResponseSetOption.newBuilder().build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void checkTx(RequestCheckTx req, StreamObserver<ResponseCheckTx> responseObserver) {
        var tx = req.getTx();
        int code = validate(tx);
        var resp = ResponseCheckTx.newBuilder()
                .setCode(code)
                .setGasWanted(1)
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void initChain(RequestInitChain req, StreamObserver<ResponseInitChain> responseObserver) {
        var resp = ResponseInitChain.newBuilder().build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void beginBlock(RequestBeginBlock req, StreamObserver<ResponseBeginBlock> responseObserver) {
        txn = env.beginTransaction();
        store = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txn);
        var resp = ResponseBeginBlock.newBuilder().build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void deliverTx(RequestDeliverTx req, StreamObserver<ResponseDeliverTx> responseObserver) {
        var tx = req.getTx();
        int code = validate(tx);
        if (code == 0) {
            List<byte[]> parts = split(tx, '=');
            System.out.println("Inserting with key " + (new String(parts.get(0), StandardCharsets.UTF_8)) + " and value " + (new String(parts.get(1), StandardCharsets.UTF_8)));
            var key = new ArrayByteIterable(parts.get(0));
            var value = new ArrayByteIterable(parts.get(1));
            store.put(txn, key, value);
        }
        var resp = ResponseDeliverTx.newBuilder()
                .setCode(code)
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void endBlock(RequestEndBlock req, StreamObserver<ResponseEndBlock> responseObserver) {
        var resp = ResponseEndBlock.newBuilder().build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void commit(RequestCommit req, StreamObserver<ResponseCommit> responseObserver) {
        txn.commit();
        var resp = ResponseCommit.newBuilder()
                .setData(ByteString.copyFrom(new byte[8]))
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void query(RequestQuery req, StreamObserver<ResponseQuery> responseObserver) {
        var k = req.getData().toByteArray();
    	System.out.println("Querying for key " + (new String(k, StandardCharsets.UTF_8)));
        var v = getPersistedValue(k);
        System.out.println("Value found: " + (new String(v, StandardCharsets.UTF_8)));
        var builder = ResponseQuery.newBuilder();
        if (v == null) {
            builder.setLog("does not exist");
        } else {
            builder.setLog("exists");
            builder.setKey(ByteString.copyFrom(k));
            builder.setValue(ByteString.copyFrom(v));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void listSnapshots(RequestListSnapshots req, StreamObserver<ResponseListSnapshots> responseObserver) {
    	if(txnSnapshot != null && !txnSnapshot.isFinished()) {
    		txnSnapshot.abort();
    	}
    	txnSnapshot = env.beginTransaction();
    	Store storeSnapshot = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txnSnapshot);
    	storeCursor = storeSnapshot.openCursor(txnSnapshot);
    	long keyValueCount = storeSnapshot.count(txnSnapshot);
    	Snapshot snapshot = Snapshot.newBuilder().setChunks((int) keyValueCount).setHeight(0).build();
    	ResponseListSnapshots response = ResponseListSnapshots.newBuilder().addAllSnapshots(new ArrayList<Snapshot>(Arrays.asList(snapshot))).build();
    	responseObserver.onNext(response);
    	responseObserver.onCompleted();
    }
    
    @Override
    public void loadSnapshotChunk(RequestLoadSnapshotChunk req, StreamObserver<ResponseLoadSnapshotChunk> responseObserver) {
    	int chunkNum = req.getChunk();
    	if(txn == null) txn = env.beginTransaction();
    	Store storeSnapshot = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txn);
    	if(chunkNum < storeSnapshot.count(txnSnapshot)) {
    		// Get key and value to Pair
    		ByteString key = ByteString.copyFrom(storeCursor.getKey().getBytesUnsafe());
    		ByteString value = ByteString.copyFrom(storeCursor.getValue().getBytesUnsafe());
    		Pair<ByteString, ByteString> pair = new Pair<>(key, value);
    		
    		// Pair to byte[]
    		ByteArrayOutputStream bos = null;
    		try {
				bos = new ByteArrayOutputStream();
				ObjectOutputStream oos = new ObjectOutputStream(bos);
				oos.writeObject(pair);
		  		oos.flush();
		  	} catch(IOException e) {
		  		e.printStackTrace();
		  	}
      		
      		// Send byte[] as response
    		ResponseLoadSnapshotChunk response;
    		response = ResponseLoadSnapshotChunk.newBuilder().setChunk(ByteString.copyFrom(bos.toByteArray())).build();
    		responseObserver.onNext(response);
    	}
    	responseObserver.onCompleted();
    }
    
    public void applySnapshotChunk(RequestApplySnapshotChunk req, StreamObserver<ResponseApplySnapshotChunk> responseObserver) {
    	ByteString chunk = req.getChunk();
    	
    	Pair<ByteString, ByteString> pair = null;
    	try {
			ByteArrayInputStream bis = new ByteArrayInputStream(chunk.toByteArray());
			ObjectInputStream ois = new ObjectInputStream(bis);
			pair = (Pair<ByteString, ByteString>) ois.readObject();
    	} catch (IOException | ClassNotFoundException e) {
    		e.printStackTrace();
    	}

    	
    	if(txn == null) txn = env.beginTransaction();
    	var store = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txn);
        boolean result = store.put(txn, new ArrayByteIterable(pair.getKey().toByteArray()), new ArrayByteIterable(pair.getValue().toByteArray()));
        
    	ResponseApplySnapshotChunk response;
    	response = ResponseApplySnapshotChunk.newBuilder().build();
    	responseObserver.onNext(response);
    	responseObserver.onCompleted();
    }


    private int validate(ByteString tx) {
        List<byte[]> parts = split(tx, '=');
        if (parts.size() != 2) {
            return 1;
        }
        byte[] key = parts.get(0);
        byte[] value = parts.get(1);

        // check if the same key=value already exists
        var stored = getPersistedValue(key);
        if (stored != null && Arrays.equals(stored, value)) {
            return 2;
        }

        return 0;
    }

    private List<byte[]> split(ByteString tx, char separator) {
        var arr = tx.toByteArray();
        int i;
        for (i = 0; i < tx.size(); i++) {
            if (arr[i] == (byte)separator) {
                break;
            }
        }
        if (i == tx.size()) {
            return Collections.emptyList();
        }
        return List.of(
                tx.substring(0, i).toByteArray(),
                tx.substring(i + 1).toByteArray()
        );
    }

    private byte[] getPersistedValue(byte[] k) {
        return env.computeInTransaction(txn -> {
            var store = env.openStore("store", StoreConfig.WITHOUT_DUPLICATES, txn);
            ByteIterable byteIterable = store.get(txn, new ArrayByteIterable(k));
            if (byteIterable == null) {
                return null;
            }
            return byteIterable.getBytesUnsafe();
        });
    }

}
