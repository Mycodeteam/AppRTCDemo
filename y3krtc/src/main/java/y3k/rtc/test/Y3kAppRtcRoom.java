/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package y3k.rtc.test;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.appspot.apprtc.AppRTCClient;
import org.appspot.apprtc.AppRTCClient.RoomConnectionParameters;
import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.PeerConnectionClient;
import org.appspot.apprtc.PeerConnectionClient.DataChannelParameters;
import org.appspot.apprtc.PeerConnectionClient.PeerConnectionParameters;
import org.appspot.apprtc.WebSocketRTCClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.VideoRenderer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import y3k.rtc.test.announcement.DataChannelAcknowledgement;
import y3k.rtc.test.announcement.DataChannelAnnouncement;
import y3k.rtc.test.channeldescription.FileChannelDescription;
import y3k.rtc.test.channeldescription.FileStreamChannelDescription;
import y3k.rtc.test.channelreader.ByteArrayChannelReader;
import y3k.rtc.test.channelreader.ChannelReader;
import y3k.rtc.test.channelreader.FileChannelReader;
import y3k.rtc.test.channelreader.FileStreamChannelReader;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class Y3kAppRtcRoom implements PeerConnectionClient.PeerConnectionEvents {

    private static final String TAG = Y3kAppRtcRoom.class.getName() + ".LOG";

    @SuppressWarnings("unused")
    private static final int STAT_CALLBACK_PERIOD = 1000;
    private final ArrayList<DataChannelAnnouncement> sentAnnouncements = new ArrayList<>();
    private final ArrayList<DataChannelAnnouncement> receivedAnnouncements = new ArrayList<>();
    private PeerConnectionClient peerConnectionClient = new PeerConnectionClient();
    private AppRTCClient appRtcClient;
    private SignalingParameters signalingParameters;
    private PeerConnectionParameters peerConnectionParameters;
    private final String roomId;
    private RoomStatus currentStatus;
    private final CallBack callBack;

    public interface CallBack {
        void onRoomStatusChanged(Y3kAppRtcRoom room, RoomStatus currentStatus);

        void onDataChannelAnnouncement(DataChannelAnnouncement dataChannelAnnouncement);

        FileChannelReader onCreateFileChannelReader(DataChannel channel, FileChannelDescription channelDescription);

        FileStreamChannelReader onCreateFileStreamChannelReader(DataChannel channel, FileStreamChannelDescription channelDescription);

        ByteArrayChannelReader onCreateByteArrayChannelReader(DataChannel channel);

        void onProxyMessage(String message);
    }

    public Y3kAppRtcRoom(Context context, String roomId, CallBack callBack) {
        this.callBack = callBack;
        this.roomId = roomId;

        boolean loopback = false;
        boolean tracing = false;

        DataChannelParameters dataChannelParameters = new DataChannelParameters(
                true, //EXTRA_ORDERED
                -1, //EXTRA_MAX_RETRANSMITS_MS
                -1, //EXTRA_MAX_RETRANSMITS
                "", //EXTRA_PROTOCOL
                false,//EXTRA_NEGOTIATED,
                -1);

        peerConnectionParameters =
                new PeerConnectionParameters(
                        false,//EXTRA_VIDEO_CALL,
                        loopback,
                        tracing,
                        0, // videoWidth
                        0, // videoHeight
                        0, //EXTRA_VIDEO_FPS
                        0, //EXTRA_VIDEO_BITRATE
                        "VP8", //EXTRA_VIDEOCODEC
                        false, //EXTRA_HWCODEC_ENABLED
                        false, //EXTRA_FLEXFEC_ENABLED
                        0, //EXTRA_AUDIO_BITRATE
                        "OPUS", //EXTRA_AUDIOCODEC
                        true, //EXTRA_NOAUDIOPROCESSING_ENABLED
                        false, //EXTRA_AECDUMP_ENABLED
                        false, //EXTRA_OPENSLES_ENABLED
                        false, //EXTRA_DISABLE_BUILT_IN_AEC
                        false, //EXTRA_DISABLE_BUILT_IN_AGC
                        false, //EXTRA_DISABLE_BUILT_IN_NS
                        false, //EXTRA_ENABLE_LEVEL_CONTROL,
                        dataChannelParameters);

        // Create connection client. Use DirectRTCClient if room name is an IP otherwise use the
        // standard WebSocketRTCClient.
//    if (loopback || !DirectRTCClient.IP_PATTERN.matcher(roomId).matches()) {
        appRtcClient = new WebSocketRTCClient(new AppRTCClient.SignalingEvents() {
            @Override
            public void onConnectedToRoom(final SignalingParameters params) {
                Log.d(TAG, "onConnectedToRoom()");
                Y3kAppRtcRoom.this.signalingParameters = params;
                Y3kAppRtcRoom.this.peerConnectionClient.createPeerConnection(null, null,
                        new ArrayList<VideoRenderer.Callbacks>(), null, Y3kAppRtcRoom.this.signalingParameters);

                if (Y3kAppRtcRoom.this.signalingParameters.initiator) {
                    // Create offer. Offer SDP will be sent to answering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    Y3kAppRtcRoom.this.peerConnectionClient.createOffer();
                } else {
                    if (params.offerSdp != null) {
                        Y3kAppRtcRoom.this.peerConnectionClient.setRemoteDescription(params.offerSdp);
                        // Create answer. Answer SDP will be sent to offering client in
                        // PeerConnectionEvents.onLocalDescription event.
                        Y3kAppRtcRoom.this.peerConnectionClient.createAnswer();
                    }
                    if (params.iceCandidates != null) {
                        // Add remote ICE candidates from room.
                        for (IceCandidate iceCandidate : params.iceCandidates) {
                            Y3kAppRtcRoom.this.peerConnectionClient.addRemoteIceCandidate(iceCandidate);
                        }
                    }
                }
                Y3kAppRtcRoom.this.onRoomStatusChanged(RoomStatus.ROOM_CONNECTED);
            }

            @Override
            public void onRemoteDescription(final SessionDescription sdp) {
                Log.d(TAG, "onRemoteDescription(" + sdp.type + "," + sdp.description + ")");
                if (Y3kAppRtcRoom.this.peerConnectionClient == null) {
                    Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
                    return;
                }
                Y3kAppRtcRoom.this.peerConnectionClient.setRemoteDescription(sdp);
                if (!Y3kAppRtcRoom.this.signalingParameters.initiator) {
                    // Create answer. Answer SDP will be sent to offering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    Y3kAppRtcRoom.this.peerConnectionClient.createAnswer();
                }
            }

            @Override
            public void onRemoteIceCandidate(final IceCandidate candidate) {
                Log.d(TAG, "onRemoteIceCandidate(" + candidate.toString() + ")");
                if (Y3kAppRtcRoom.this.peerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
                    return;
                }
                peerConnectionClient.addRemoteIceCandidate(candidate);
            }

            @Override
            public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
                Log.d(TAG, "onRemoteIceCandidatesRemoved(" + Arrays.toString(candidates) + ")");
                if (Y3kAppRtcRoom.this.peerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.");
                    return;
                }
                Y3kAppRtcRoom.this.peerConnectionClient.removeRemoteIceCandidates(candidates);
            }

            @Override
            public void onChannelClose() {
                Log.d(TAG, "onChannelClose()");
                Y3kAppRtcRoom.this.disconnect();
            }

            @Override
            public void onChannelError(final String description) {
                Log.d(TAG, "roomEventListener.onChannelError(" + description + ")");
            }
        });
//    } else {
//      Log.i(TAG, "Using DirectRTCClient because room name looks like an IP.");
//      appRtcClient = new DirectRTCClient(this);
//    }
        // Create connection parameters.
        RoomConnectionParameters roomConnectionParameters = new RoomConnectionParameters("https://appr.tc", roomId, false);

        if (loopback) {
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.networkIgnoreMask = 0;
            peerConnectionClient.setPeerConnectionFactoryOptions(options);
        }
        peerConnectionClient.createPeerConnectionFactory(
                context, peerConnectionParameters, Y3kAppRtcRoom.this);

        appRtcClient.connectToRoom(roomConnectionParameters);
        this.currentStatus = RoomStatus.CONNECTING;
        this.onRoomStatusChanged(RoomStatus.CONNECTING);
    }

    public String getRoomId() {
        return this.roomId;
    }

    public RoomStatus getCurrentStatus() {
        return this.currentStatus;
    }

    public enum RoomStatus {
        CONNECTING, ROOM_CONNECTED, ICE_CONNECTED, PEER_CONNECTED, DISCONNECTED
    }

    protected void onDestroy() {
        disconnect();
    }

    private void disconnect() {
        if (appRtcClient != null) {
            appRtcClient.disconnectFromRoom();
            appRtcClient = null;
        }
        if (peerConnectionClient != null) {
            peerConnectionClient.close();
            peerConnectionClient = null;
        }
        if(this.currentDataChannels.size()!=0) {
            for (DataChannel channel : this.currentDataChannels) {
                if (channel.state() != DataChannel.State.CLOSED && channel.state() != DataChannel.State.CLOSING) {
                    channel.close();
                }
            }
            this.currentDataChannels.clear();
        }
        this.onRoomStatusChanged(RoomStatus.DISCONNECTED);
    }

    @Override
    public void onLocalDescription(final SessionDescription sdp) {
        Log.d(TAG, "onLocalDescription(" + sdp.type + "," + sdp.description + ")");
        if (appRtcClient != null) {
            if (signalingParameters.initiator) {
                appRtcClient.sendOfferSdp(sdp);
            } else {
                appRtcClient.sendAnswerSdp(sdp);
            }
        }
        if (peerConnectionParameters.videoMaxBitrate > 0) {
            Log.d(TAG, "Set video maximum bitrate: " + peerConnectionParameters.videoMaxBitrate);
            peerConnectionClient.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate);
        }
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        Log.d(TAG, "onIceCandidate(" + candidate.toString() + ")");
        if (appRtcClient != null) {
            appRtcClient.sendLocalIceCandidate(candidate);
        }
    }

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
        Log.d(TAG, "onIceCandidatesRemoved(" + Arrays.toString(candidates) + ")");
        if (appRtcClient != null) {
            appRtcClient.sendLocalIceCandidateRemovals(candidates);
        }
    }

    @Override
    public void onIceConnected() {
        Log.d(TAG, "onIceConnected()");
        this.onRoomStatusChanged(RoomStatus.ICE_CONNECTED);
//        peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
    }

    @Override
    public void onIceDisconnected() {
        Log.d(TAG, "onIceDisconnected()");
        disconnect();
    }

    @Override
    public void onPeerConnectionClosed() {
        Log.d(TAG, "onPeerConnectionClosed()");
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
        Log.d(TAG, "onPeerConnectionStatsReady(" + Arrays.toString(reports) + ");");
        this.onRoomStatusChanged(RoomStatus.PEER_CONNECTED);
    }

    private final ArrayList<DataChannel> currentDataChannels = new ArrayList<>();

    public ArrayList<DataChannel> getCurrentDataChannels() {
        return currentDataChannels;
    }

    @Override
    public void onDataChannel(final DataChannel dataChannel) {
        for (DataChannel currentDataChannel : new ArrayList<>(this.currentDataChannels)) {
            if (currentDataChannel.state() == DataChannel.State.CLOSED) {
                this.currentDataChannels.remove(currentDataChannel);
            }
        }
        this.currentDataChannels.add(dataChannel);
        Log.d(TAG, "onDataChannel(" + dataChannel.id() + "," + dataChannel.label() + "," + dataChannel.state() + ")");
        if (dataChannel.label().equals("ApprtcDemo data")) {
            return;
        }
        if (dataChannel.label().equals("Manage")) {
            Log.d(Y3kAppRtcRoom.TAG, "got Manage Channel!!");
            dataChannel.registerObserver(new DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long l) {

                }

                @Override
                public void onStateChange() {
                    Log.d(TAG, "Manage Channel onStateChanged(" + dataChannel.state().name() + ")");
                }

                @Override
                public void onMessage(DataChannel.Buffer buffer) {
                    Log.d(TAG, "Manage Channel onMessage!!");
                    byte[] receivedBytes = new byte[buffer.data.remaining()];
                    buffer.data.get(receivedBytes);
                    String receivedString = new String(receivedBytes);
                    try {
                        Y3kAppRtcRoom.this.onChannelAnnouncement(new DataChannelAnnouncement(Y3kAppRtcRoom.this, new JSONObject(receivedString)));
                    } catch (JSONException | IllegalArgumentException e) {
                        e.printStackTrace();
                        try {
                            Y3kAppRtcRoom.this.onChannelAcknowledgement(new DataChannelAcknowledgement(new JSONObject(receivedString)));
                        } catch (JSONException | IllegalArgumentException e1) {
                            e1.printStackTrace();
                        }
                    }
                }
            });
        } else if (dataChannel.label().equals("MessageProxy")) {
            Log.d(Y3kAppRtcRoom.TAG, "got MessageProxy Channel!!");
            dataChannel.registerObserver(new DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long l) {

                }

                @Override
                public void onStateChange() {
                    Log.d(TAG, "Manage Channel onStateChanged(" + dataChannel.state().name() + ")");
                }

                @Override
                public void onMessage(DataChannel.Buffer buffer) {
                    Log.d(TAG, "Manage Channel onMessage!!");
                    byte[] receivedBytes = new byte[buffer.data.remaining()];
                    buffer.data.get(receivedBytes);
                    String receivedString = new String(receivedBytes);
                    Y3kAppRtcRoom.this.onProxyMessage(receivedString);
                }
            });
        } else {
            try {
                Y3kAppRtcRoom.this.onFileChannelConnected(dataChannel, new FileChannelDescription(new JSONObject(dataChannel.label())));
                return;
            } catch (JSONException e) {
                e.printStackTrace();
            }
            try {
                Y3kAppRtcRoom.this.onFileStreamChannelConnected(dataChannel, new FileStreamChannelDescription(new JSONObject(dataChannel.label())));
                return;
            } catch (JSONException e) {
                e.printStackTrace();
            }
            Y3kAppRtcRoom.this.onRawChannelConnected(dataChannel);
        }
    }

    private void onChannelAnnouncement(DataChannelAnnouncement announcement) {
        try {
            Log.d(TAG, "onChannelAnnouncement(" + announcement.toJSONObject().toString() + ")");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        this.callBack.onDataChannelAnnouncement(announcement);
    }

    private void onProxyMessage(String message) {
        Log.d(TAG, "onProxyMessage(" + message + ")");
        this.callBack.onProxyMessage(message);
    }

    private void onFileChannelConnected(final DataChannel dataChannel, final FileChannelDescription fileChannelDescription) {
        ChannelReader channelReader = this.callBack.onCreateFileChannelReader(dataChannel, fileChannelDescription);
        if (channelReader != null) {
            channelReader.start();
        }
    }

    private void onFileStreamChannelConnected(final DataChannel dataChannel, final FileStreamChannelDescription fileStreamChannelDescription) {
        ChannelReader channelReader = this.callBack.onCreateFileStreamChannelReader(dataChannel, fileStreamChannelDescription);
        if (channelReader != null) {
            channelReader.start();
        }
    }

    private void onRawChannelConnected(final DataChannel dataChannel) {
        ChannelReader channelReader = this.callBack.onCreateByteArrayChannelReader(dataChannel);
        if (channelReader != null) {
            channelReader.start();
        }
    }

    public void onAnnouncementAccepted(DataChannelAnnouncement announcement) {
        Y3kAppRtcRoom.this.receivedAnnouncements.add(announcement);
        switch (announcement.getType()) {
            case File:
            case FileStream:
                try {
                    Y3kAppRtcRoom.this.peerConnectionClient.getManageDataChannel().send(new DataChannel.Buffer(ByteBuffer.wrap(new DataChannelAcknowledgement(announcement, DataChannelAcknowledgement.Reply.NOTED).toJSONObject().toString().getBytes()), true));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;
            default:
                break;
        }
    }

    public void onAnnouncementDeclined(DataChannelAnnouncement announcement) {
        switch (announcement.getType()) {
            case File:
            case FileStream:
                try {
                    Y3kAppRtcRoom.this.peerConnectionClient.getManageDataChannel().send(new DataChannel.Buffer(ByteBuffer.wrap(new DataChannelAcknowledgement(announcement, DataChannelAcknowledgement.Reply.REFUSE).toJSONObject().toString().getBytes()), true));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;
            default:
                break;
        }
    }

    private void onChannelAcknowledgement(DataChannelAcknowledgement acknowledgement) {
        try {
            Log.d(TAG, "onChannelAcknowledgement(" + acknowledgement.toJSONObject().toString() + ")");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        for (final DataChannelAnnouncement announcement : new ArrayList<>(this.sentAnnouncements)) {
            if (announcement.getChannelDescription().getUUID().compareTo(acknowledgement.getChannelUUID()) == 0) {
                switch (acknowledgement.getReply()) {
                    case NOTED:
                        if (announcement.getChannelDescription() instanceof FileChannelDescription) {
                            final File localFile = ((FileChannelDescription) announcement.getChannelDescription()).getLocalFile();
                            if (localFile != null) {
                                try {
                                    final FileInputStream fileInputStream = new FileInputStream(localFile);
                                    final DataChannel dataChannel = Y3kAppRtcRoom.this.newDataChannel(announcement.getChannelDescription().toJSONObject().toString());
                                    new AsyncTask<Void, Integer, Void>() {
                                        long totalSentByteCount = 0;

                                        @Override
                                        protected void onProgressUpdate(Integer... values) {
                                            super.onProgressUpdate(values);
                                            for (Integer progress : values) {
                                                this.totalSentByteCount += progress;
                                            }
                                        }

                                        @Override
                                        protected Void doInBackground(Void... params) {
                                            while (dataChannel.state() != DataChannel.State.OPEN) {
                                                try {
                                                    Thread.sleep(1000);
                                                } catch (InterruptedException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                            byte[] readBuffer = new byte[51200];
                                            try {
                                                for (int read; (read = fileInputStream.read(readBuffer)) > 0; ) {
                                                    Log.d("SendFile", "sent " + read);
                                                    if (dataChannel.state() == DataChannel.State.CLOSED) {
                                                        // TODO
                                                    }
                                                    ByteBuffer byteBuffer = ByteBuffer.wrap(Arrays.copyOf(readBuffer, read));
                                                    dataChannel.send(new DataChannel.Buffer(byteBuffer, true));
                                                    this.publishProgress(read);
                                                    while (dataChannel.bufferedAmount() > 0) {
                                                        Log.d("SendFile", "dataChannel.bufferedAmount = " + dataChannel.bufferedAmount());
                                                        try {
                                                            Thread.sleep(50);
                                                        } catch (InterruptedException e) {
                                                            e.printStackTrace();
                                                        }
                                                    }
                                                }
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            dataChannel.close();
                                            try {
                                                fileInputStream.close();
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            return null;
                                        }
                                    }.execute();
                                } catch (IllegalStateException | FileNotFoundException | JSONException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                Log.d(TAG, "onChannelAcknowledgement localFile==null!!");
                            }
                        } else if (announcement.getChannelDescription() instanceof FileStreamChannelDescription) {
                            final InputStream localInputStream = ((FileStreamChannelDescription) announcement.getChannelDescription()).getFileStream();
                            if (localInputStream != null) {
                                try {
                                    final DataChannel dataChannel = Y3kAppRtcRoom.this.newDataChannel(announcement.getChannelDescription().toJSONObject().toString());
                                    new AsyncTask<Void, Integer, Void>() {
                                        long totalSentByteCount = 0;

                                        @Override
                                        protected void onProgressUpdate(Integer... values) {
                                            super.onProgressUpdate(values);
                                            for (Integer progress : values) {
                                                this.totalSentByteCount += progress;
                                            }
                                        }

                                        @Override
                                        protected Void doInBackground(Void... params) {
                                            while (dataChannel.state() != DataChannel.State.OPEN) {
                                                try {
                                                    Thread.sleep(1000);
                                                } catch (InterruptedException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                            byte[] readBuffer = new byte[51200];
                                            try {
                                                for (int read; (read = localInputStream.read(readBuffer)) > 0; ) {
                                                    Log.d("SendFile", "sent " + read);
                                                    if (dataChannel.state() == DataChannel.State.CLOSED) {
                                                        // TODO
                                                    }
                                                    ByteBuffer byteBuffer = ByteBuffer.wrap(Arrays.copyOf(readBuffer, read));
                                                    dataChannel.send(new DataChannel.Buffer(byteBuffer, true));
                                                    this.publishProgress(read);
                                                    while (dataChannel.bufferedAmount() > 0) {
                                                        Log.d("SendFile", "dataChannel.bufferedAmount = " + dataChannel.bufferedAmount());
                                                        try {
                                                            Thread.sleep(50);
                                                        } catch (InterruptedException e) {
                                                            e.printStackTrace();
                                                        }
                                                    }
                                                }
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            dataChannel.close();
                                            try {
                                                localInputStream.close();
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            return null;
                                        }
                                    }.execute();
                                } catch (IllegalStateException | JSONException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                Log.d(TAG, "onChannelAcknowledgement localInputStream==null!!");
                            }
                        } else {
                            // TODO : else
                        }
                        break;
                    case REFUSE:
                        break;
                    default:
                        break;
                }
            }
            this.sentAnnouncements.remove(announcement);
            break;
        }
    }

    @Override
    public void onPeerConnectionError(final String description) {
        Log.d(TAG, "onPeerConnectionError(" + description + ")");
    }

    public DataChannel newDataChannel(String name) throws IllegalStateException {
        if (this.peerConnectionClient == null && this.currentStatus != RoomStatus.PEER_CONNECTED) {
            throw new IllegalStateException("peerConnectionClient==null , you fool!!");
        } else {
            DataChannel.Init dataChannelInit = new DataChannel.Init();
            dataChannelInit.ordered = peerConnectionParameters.dataChannelParameters.ordered;
            dataChannelInit.negotiated = peerConnectionParameters.dataChannelParameters.negotiated;
            dataChannelInit.maxRetransmits = peerConnectionParameters.dataChannelParameters.maxRetransmits;
            dataChannelInit.maxRetransmitTimeMs = peerConnectionParameters.dataChannelParameters.maxRetransmitTimeMs;
            dataChannelInit.id = peerConnectionParameters.dataChannelParameters.id;
            dataChannelInit.protocol = peerConnectionParameters.dataChannelParameters.protocol;
            return peerConnectionClient.getPeerConnection().createDataChannel(name, dataChannelInit);
        }
    }

    private void onRoomStatusChanged(RoomStatus newStatus) {
        Log.d(TAG, "onRoomStatusChanged oldStatus=" + this.currentStatus.name() + ",newStatus=" + newStatus.name() + ".");
        this.currentStatus = newStatus;
        this.callBack.onRoomStatusChanged(this, this.currentStatus);
    }

    public boolean openSendFileStreamAnnouncement(InputStream fileStream, String fileName, String filePath, long fileLength) {
        try {
            DataChannelAnnouncement announcement = new DataChannelAnnouncement(this, new FileStreamChannelDescription(UUID.randomUUID(), fileName, filePath, fileStream, fileLength));
            this.peerConnectionClient.getManageDataChannel().send(new DataChannel.Buffer(ByteBuffer.wrap(announcement.toJSONObject().toString().getBytes()), true));
            this.sentAnnouncements.add(announcement);
            return true;
        } catch (IllegalArgumentException | JSONException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean openSendFileAnnouncement(File file) {
        try {
            DataChannelAnnouncement announcement = new DataChannelAnnouncement(this, new FileChannelDescription(UUID.randomUUID(), file.getName(), file, file.length()));
            this.peerConnectionClient.getManageDataChannel().send(new DataChannel.Buffer(ByteBuffer.wrap(announcement.toJSONObject().toString().getBytes()), true));
            this.sentAnnouncements.add(announcement);
            return true;
        } catch (IllegalArgumentException | JSONException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean sendMessageThroughProxyChannel(String message){
        try {
            this.peerConnectionClient.getMessageDataChannel().send(new DataChannel.Buffer(ByteBuffer.wrap(message.getBytes()),true));
            return true;
        } catch (IllegalStateException e) {
            e.printStackTrace();
            return false;
        }
    }
}
