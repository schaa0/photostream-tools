/*
 * The MIT License
 *
 * Copyright (c) 2016 Andreas Schattney
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hochschuledarmstadt.photostream_tools;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import hochschuledarmstadt.photostream_tools.callback.OnCommentsResultListener;
import hochschuledarmstadt.photostream_tools.callback.OnPhotoListener;
import hochschuledarmstadt.photostream_tools.callback.OnPhotoUploadListener;
import hochschuledarmstadt.photostream_tools.callback.OnPhotoLikeListener;
import hochschuledarmstadt.photostream_tools.callback.OnPhotosResultListener;
import hochschuledarmstadt.photostream_tools.callback.OnRequestListener;
import hochschuledarmstadt.photostream_tools.callback.OnSearchPhotosResultListener;
import hochschuledarmstadt.photostream_tools.model.Comment;
import hochschuledarmstadt.photostream_tools.model.HttpResult;
import hochschuledarmstadt.photostream_tools.model.Photo;
import hochschuledarmstadt.photostream_tools.model.PhotoQueryResult;
import io.socket.client.IO;
import io.socket.engineio.client.transports.WebSocket;

class PhotoStreamClient implements AndroidSocket.OnMessageListener, IPhotoStreamClient {

    private static final String TAG = PhotoStreamService.class.getName();
    public static final String INTENT_NEW_PHOTO = "hochschuledarmstadt.photostream_tools.intent.NEW_PHOTO";

    private final String installationId;
    private final Context context;
    private final String photoStreamUrl;
    private DbConnection dbConnection;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private String lastQuery;

    public PhotoStreamClient(Context context, String photoStreamUrl, DbConnection dbConnection, String installationId){
        this.context = context;
        this.photoStreamUrl = photoStreamUrl;
        this.dbConnection = dbConnection;
        this.installationId = installationId;
    }

    private ArrayList<OnPhotosResultListener> onPhotosResultListeners = new ArrayList<>();
    private ArrayList<OnPhotoLikeListener> onPhotoLikeListeners = new ArrayList<>();
    private ArrayList<OnCommentsResultListener> onCommentsResultListeners = new ArrayList<>();
    private ArrayList<OnPhotoUploadListener> onPhotoUploadListeners = new ArrayList<>();
    private ArrayList<OnSearchPhotosResultListener> onSearchPhotosListeners = new ArrayList<>();

    private WebSocketClient webSocketClient;

    void destroy() {
        openRequests.clear();
        if (webSocketClient != null)
            webSocketClient.disconnect();
    }

    private final HashMap<RequestType, Integer> openRequests = new HashMap<>();

    @Override
    public void addOnPhotoLikeListener(OnPhotoLikeListener onPhotoLikeListener) {
        if (!onPhotoLikeListeners.contains(onPhotoLikeListener))
            onPhotoLikeListeners.add(onPhotoLikeListener);
        if (hasOpenRequestsOfType(RequestType.LIKE))
            onPhotoLikeListener.onShowProgressDialog();
    }

    @Override
    public void removeOnPhotoLikeListener(OnPhotoLikeListener onPhotoLikeListener) {
        if (onPhotoLikeListeners.contains(onPhotoLikeListener))
            onPhotoLikeListeners.remove(onPhotoLikeListener);
    }

    @Override
    public void addOnGetCommentsResultListener(OnCommentsResultListener onCommentsResultListener){
        if (!onCommentsResultListeners.contains(onCommentsResultListener))
            onCommentsResultListeners.add(onCommentsResultListener);
        if (hasOpenRequestsOfType(RequestType.COMMENT))
            onCommentsResultListener.onShowProgressDialog();
    }

    @Override
    public void removeOnGetCommentsResultListener(OnCommentsResultListener onCommentsResultListener){
        if (onCommentsResultListeners.contains(onCommentsResultListener))
            onCommentsResultListeners.remove(onCommentsResultListener);
    }

    @Override
    public void addOnPhotosResultListener(OnPhotosResultListener onPhotosResultListener) {
        if (!onPhotosResultListeners.contains(onPhotosResultListener))
            onPhotosResultListeners.add(onPhotosResultListener);
        if (hasOpenRequestsOfType(RequestType.PHOTOS))
            onPhotosResultListener.onShowProgressDialog();
    }

    @Override
    public void removeOnPhotosResultListener(OnPhotosResultListener onPhotosResultListener){
        if (onPhotosResultListeners.contains(onPhotosResultListener))
            onPhotosResultListeners.remove(onPhotosResultListener);
    }

    @Override
    public void addOnSearchPhotosResultListener(OnSearchPhotosResultListener onSearchPhotosResultListener){
        if (!onSearchPhotosListeners.contains(onSearchPhotosResultListener))
            onSearchPhotosListeners.add(onSearchPhotosResultListener);
        if (hasOpenRequestsOfType(RequestType.SEARCH))
            onSearchPhotosResultListener.onShowProgressDialog();
    }

    @Override
    public void removeOnSearchPhotosResultListener(OnSearchPhotosResultListener onSearchPhotosResultListener){
        if (onSearchPhotosListeners.contains(onSearchPhotosResultListener))
            onSearchPhotosListeners.remove(onSearchPhotosResultListener);
    }

    void bootstrap(){
        webSocketClient = new WebSocketClient(photoStreamUrl, installationId, this);
        webSocketClient.connect();
    }

    @Override
    public void getPhotos() {
        final RequestType requestType = RequestType.PHOTOS;
        GetPhotosAsyncTask task = new GetPhotosAsyncTask(context, installationId, photoStreamUrl, 1, new GetPhotosAsyncTask.GetPhotosCallback() {
            @Override
            public void onPhotosResult(PhotoQueryResult queryResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotos(queryResult);
            }

            @Override
            public void onPhotosError(HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotosFailed(httpResult);
            }

        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    @Override
    public void getMorePhotos() {
        final RequestType requestType = RequestType.PHOTOS;
        GetMorePhotosAsyncTask task = new GetMorePhotosAsyncTask(context, installationId, photoStreamUrl, new GetPhotosAsyncTask.GetPhotosCallback() {
            @Override
            public void onPhotosResult(PhotoQueryResult queryResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotos(queryResult);
            }

            @Override
            public void onPhotosError(HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotosFailed(httpResult);
            }
        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    private void addOpenRequest(RequestType requestType) {
        synchronized (openRequests) {
            int n = 1;
            if (openRequests.containsKey(requestType)) {
                n = openRequests.get(requestType);
                n++;
                openRequests.remove(requestType);
            }
            openRequests.put(requestType, n);
        }
    }

    private int getOpenRequest(RequestType requestType){
        if (openRequests.containsKey(requestType)) {
            return openRequests.get(requestType);
        }
        return 0;
    }

    private void removeOpenRequest(RequestType requestType){
        synchronized (openRequests) {
            if (openRequests.containsKey(requestType)) {
                int amount = openRequests.get(requestType);
                amount--;
                openRequests.remove(requestType);
                if (amount > 0)
                    openRequests.put(requestType, amount);
            }
        }
    }

    private void determineShouldDismissProgressDialog(RequestType requestType) {
        synchronized (openRequests) {
            int amount = getOpenRequest(requestType);
            if (amount == 0)
                notifyDismissProgressDialog(requestType);
        }
    }

    private void determineShouldShowProgressDialog(RequestType requestType) {
        synchronized (openRequests) {
            int amount = getOpenRequest(requestType);
            if (amount == 1)
                notifyShowProgressDialog(requestType);
        }
    }

    private void notifyShowProgressDialog(RequestType requestType) {
        Collection<OnRequestListener> onRequestListeners = getCallbacksForType(requestType, OnRequestListener.class);
        for (OnRequestListener onRequestListener : onRequestListeners){
            onRequestListener.onShowProgressDialog();
        }
    }

    private void notifyDismissProgressDialog(final RequestType requestType) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Collection<OnRequestListener> onRequestListeners = getCallbacksForType(requestType, OnRequestListener.class);
                for (OnRequestListener onRequestListener : onRequestListeners){
                    onRequestListener.onDismissProgressDialog();
                }
            }
        }, 200);
    }

    private <T> Collection<T> getCallbacksForType(RequestType requestType, Class<T> clazz) {
        Collection<T> result = null;
        try{
            switch(requestType){
                case PHOTOS:
                    result = cast(onPhotosResultListeners, clazz);
                    break;
                case COMMENT:
                    result = cast(onCommentsResultListeners, clazz);
                    break;
                case LIKE:
                    result = cast(onPhotoLikeListeners, clazz);
                    break;
                case UPLOAD:
                    result = cast(onPhotoUploadListeners, clazz);
                    break;
                case SEARCH:
                    result = cast(onSearchPhotosListeners, clazz);
            }
        }catch(Exception e){
            result = new ArrayList<>();
        }
        return result;
    }

    private void notifyOnPhotosFailed(HttpResult httpResult) {
        for (OnPhotosResultListener resultListener : onPhotosResultListeners)
            resultListener.onReceivePhotosFailed(httpResult);
    }

    private void notifyOnPhotos(PhotoQueryResult photoQueryResult) {
        for (OnPhotosResultListener onPhotosResultListener : onPhotosResultListeners){
            onPhotosResultListener.onPhotosReceived(photoQueryResult);
        }
    }

    private static <T> Collection<T> cast(Collection<?> collection, Class<T> clazz){
        return (Collection<T>) collection;
    }

    private void notifyOnNewPhoto(Photo photo) {
        if (areListenersRegisteredToNotify()) {
            RequestType[] requestTypes = new RequestType[]{RequestType.PHOTOS, RequestType.SEARCH};
            for (RequestType requestType : requestTypes){
                Collection<OnPhotoListener> listeners = getCallbacksForType(requestType, OnPhotoListener.class);
                if (listeners != null) {
                    for (OnPhotoListener photoListener : listeners)
                        photoListener.onNewPhotoReceived(photo);
                }
            }
        }else if(photoIsNotFromThisUser(photo)){
            Intent newPhotoIntent = new Intent(INTENT_NEW_PHOTO);
            newPhotoIntent.setPackage(context.getPackageName());
            context.sendBroadcast(newPhotoIntent);
        }
    }

    private boolean photoIsNotFromThisUser(Photo photo) {
        return !photo.isDeleteable();
    }

    private boolean areListenersRegisteredToNotify() {
        return onPhotosResultListeners.size() > 0;
    }

    private void notifyOnPhotoLiked(int photoId) {
        for (OnPhotoLikeListener onPhotoLikeListener : onPhotoLikeListeners){
            onPhotoLikeListener.onPhotoLiked(photoId);
        }
    }

    private void notifyOnPhotoDisliked(int photoId) {
        for (OnPhotoLikeListener onPhotoLikeListener : onPhotoLikeListeners){
            onPhotoLikeListener.onPhotoDisliked(photoId);
        }
    }

    @Override
    public void likePhoto(int photoId) {
        final RequestType requestType = RequestType.LIKE;
        LikeTable likeTable = new LikeTable(DbConnection.getInstance(context));
        LikeOrDislikePhotoAsyncTask task = new LikePhotoAsyncTask(likeTable, installationId, photoStreamUrl, photoId, new LikeOrDislikePhotoAsyncTask.OnVotePhotoResultListener() {

            @Override
            public void onPhotoLiked(int photoId) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoLiked(photoId);
            }

            @Override
            public void onPhotoDisliked(int photoId) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoDisliked(photoId);
            }

            @Override
            public void onPhotoLikeFailed(int photoId, HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoLikeFailed(photoId, httpResult);
            }

        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    private void notifyOnPhotoLikeFailed(int photoId, HttpResult httpResult) {
        for (OnPhotoLikeListener listener : onPhotoLikeListeners)
            listener.onPhotoLikeFailed(photoId, httpResult);
    }

    @Override
    public boolean hasUserAlreadyLikedPhoto(int photoId){
        LikeTable likeTable = new LikeTable(dbConnection);
        likeTable.openDatabase();
        boolean alreadyVoted = likeTable.hasUserLikedPhoto(photoId);
        likeTable.closeDatabase();
        return alreadyVoted;
    }

    @Override
    public void getComments(int photoId){
        final RequestType requestType = RequestType.COMMENT;
        GetCommentsAsyncTask task = new GetCommentsAsyncTask(installationId, photoStreamUrl, photoId, new GetCommentsAsyncTask.OnCommentsResultListener() {

            @Override
            public void onGetComments(int photoId, List<Comment> comments) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnComments(photoId, comments);
            }

            @Override
            public void onGetCommentsFailed(int photoId, HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnCommentsFailed(photoId, httpResult);
            }

        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    private void notifyOnCommentsFailed(int photoId, HttpResult httpResult) {
        for (OnCommentsResultListener listener : onCommentsResultListeners)
            listener.onGetCommentsFailed(photoId, httpResult);
    }

    private void notifyOnComments(int photoId, List<Comment> comments) {
        for (OnCommentsResultListener listener : onCommentsResultListeners)
            listener.onGetComments(photoId, comments);
    }

    @Override
    public void dislikePhoto(int photoId) {
        final RequestType requestType = RequestType.LIKE;
        LikeTable likeTable = new LikeTable(DbConnection.getInstance(context));
        LikeOrDislikePhotoAsyncTask task = new DislikePhotoAsyncTask(likeTable, installationId, photoStreamUrl, photoId, new LikeOrDislikePhotoAsyncTask.OnVotePhotoResultListener() {
            @Override
            public void onPhotoLiked(int photoId) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoLiked(photoId);
            }

            @Override
            public void onPhotoDisliked(int photoId) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoDisliked(photoId);
            }

            @Override
            public void onPhotoLikeFailed(int photoId, HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoLikeFailed(photoId, httpResult);
            }
        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    @Override
    public void deleteComment(Comment comment) {
        final RequestType requestType = RequestType.COMMENT;
        DeleteCommentAsyncTask task = new DeleteCommentAsyncTask(installationId, photoStreamUrl, comment.getId(), new DeleteCommentAsyncTask.OnDeleteCommentResultListener() {
            @Override
            public void onCommentDeleted(int commentId) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnCommentDeleted(commentId);
            }

            @Override
            public void onCommentDeleteFailed(int commentId, HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnCommentDeleteFailed(commentId, httpResult);
            }

        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    @Override
    public void deletePhoto(final Photo photo){
        final RequestType requestType = RequestType.PHOTOS;
        final int photoId = photo.getId();
        DeletePhotoAsyncTask task = new DeletePhotoAsyncTask(installationId, photoStreamUrl, photoId, new DeletePhotoAsyncTask.OnDeletePhotoResultListener() {

            @Override
            public void onPhotoDeleted(int photoId) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnPhotoDeleted(photoId);
            }

            @Override
            public void onPhotoDeleteFailed(int photoId, HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnDeletePhotoFailed(photoId, httpResult);
            }

        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    private void notifyOnDeletePhotoFailed(int photoId, HttpResult httpResult) {
        RequestType[] requestTypes = new RequestType[]{RequestType.PHOTOS, RequestType.SEARCH};
        for (RequestType requestType : requestTypes){
            Collection<OnPhotoListener> listeners = getCallbacksForType(requestType, OnPhotoListener.class);
            if (listeners != null) {
                for (OnPhotoListener photoListener : listeners)
                    photoListener.onPhotoDeleteFailed(photoId, httpResult);
            }
        }
    }

    private void notifyOnCommentDeleted(int commentId) {
        for (OnCommentsResultListener listener : onCommentsResultListeners)
            listener.onCommentDeleted(commentId);
    }

    private void notifyOnCommentDeleteFailed(int commentId, HttpResult httpResult) {
        for (OnCommentsResultListener listener : onCommentsResultListeners)
            listener.onCommentDeleteFailed(commentId, httpResult);
    }

    @Override
    public void sendComment(int photoId, String comment) {
        final RequestType requestType = RequestType.COMMENT;
        StoreCommentAsyncTask task = new StoreCommentAsyncTask(installationId, photoStreamUrl, photoId, comment, new StoreCommentAsyncTask.OnCommentSentListener() {
            @Override
            public void onCommentSent(Comment comment) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnNewComment(comment);
            }

            @Override
            public void onSendCommentFailed(HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnCommentSentFailed(httpResult);
            }
        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute();
    }

    private void notifyOnCommentSentFailed(HttpResult httpResult) {
        for (OnCommentsResultListener listener : onCommentsResultListeners)
            listener.onSendCommentFailed(httpResult);
    }

    private void notifyOnNewComment(Comment comment) {
        for (OnCommentsResultListener listener : onCommentsResultListeners)
            listener.onNewComment(comment);
    }

    @Override
    public void addOnPhotoUploadListener(OnPhotoUploadListener onPhotoUploadListener) {
        if (!onPhotoUploadListeners.contains(onPhotoUploadListener)) {
            onPhotoUploadListeners.add(onPhotoUploadListener);
            if (hasOpenRequestsOfType(RequestType.UPLOAD))
                onPhotoUploadListener.onShowProgressDialog();
        }
    }

    @Override
    public void removeOnPhotoUploadListener(OnPhotoUploadListener onPhotoUploadListener) {
        if (onPhotoUploadListeners.contains(onPhotoUploadListener))
            onPhotoUploadListeners.remove(onPhotoUploadListener);
    }

    @Override
    public boolean hasOpenRequestsOfType(RequestType requestType) {
        if (openRequests.containsKey(requestType))
            return openRequests.get(requestType) > 0;
        return false;
    }

    @Override
    public void searchMorePhotos(){
        final RequestType requestType = RequestType.SEARCH;
        SearchMorePhotosAsyncTask searchPhotosAsyncTask = new SearchMorePhotosAsyncTask(context, installationId, photoStreamUrl, new SearchPhotosAsyncTask.OnSearchPhotosResultCallback() {
            @Override
            public void onSearchPhotosResult(PhotoQueryResult photoQueryResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnSearchPhotosResult(photoQueryResult);
            }

            @Override
            public void onSearchPhotosError(HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnSearchPhotosError(lastQuery, httpResult);
            }
        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        searchPhotosAsyncTask.execute();
    }

    @Override
    public void searchPhotos(final String query) {
        final RequestType requestType = RequestType.SEARCH;
        SearchPhotosAsyncTask searchPhotosAsyncTask = new SearchPhotosAsyncTask(context, installationId, photoStreamUrl, query, 1, new SearchPhotosAsyncTask.OnSearchPhotosResultCallback() {
            @Override
            public void onSearchPhotosResult(PhotoQueryResult photoQueryResult) {
                lastQuery = query;
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnSearchPhotosResult(photoQueryResult);
            }

            @Override
            public void onSearchPhotosError(HttpResult httpResult) {
                lastQuery = query;
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                notifyOnSearchPhotosError(query, httpResult);
            }
        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        searchPhotosAsyncTask.execute();
    }

    private void notifyOnSearchPhotosError(String query, HttpResult httpResult) {
        for (OnSearchPhotosResultListener listener : onSearchPhotosListeners)
            listener.onReceiveSearchedPhotosFailed(query, httpResult);
    }

    private void notifyOnSearchPhotosResult(PhotoQueryResult photoQueryResult) {
        for (OnSearchPhotosResultListener listener : onSearchPhotosListeners)
            listener.onSearchedPhotosReceived(photoQueryResult);
    }

    private static class WebSocketClient {

        private final String installationId;
        private final String url;
        private final AndroidSocket.OnMessageListener messageListener;
        private AndroidSocket androidSocket;

        public WebSocketClient(String url, String installationId, AndroidSocket.OnMessageListener messageListener){
            this.url = url;
            this.installationId = installationId;
            this.messageListener = messageListener;
        }

        public boolean connect() {
            IO.Options options = new IO.Options();
            try {
                //options.sslContext = AndroidSocket.createSslContext();
                options.reconnectionDelay = 5000;
                options.reconnection = true;
                //options.secure = true;
                options.transports = new String[]{WebSocket.NAME};
                options.reconnectionAttempts = 12;

                URI uri = URI.create(url + "/?token=" + installationId);
                if (androidSocket == null)
                    androidSocket = new AndroidSocket(options, uri, messageListener);
                return androidSocket.connect();
            } catch (KeyManagementException e) {
                Log.e(PhotoStreamService.class.getName(), e.toString());
            } catch (NoSuchAlgorithmException e) {
                Log.e(PhotoStreamService.class.getName(), e.toString());
            } catch (URISyntaxException e) {
                Log.e(PhotoStreamService.class.getName(), e.toString());
            }
            return false;
        }

        public void disconnect() {
            androidSocket.disconnect();
        }
    }

    private void notifyOnPhotoDeleted(int photoId) {
        RequestType[] requestTypes = new RequestType[]{RequestType.PHOTOS, RequestType.SEARCH};
        for (RequestType requestType : requestTypes){
            Collection<OnPhotoListener> listeners = getCallbacksForType(requestType, OnPhotoListener.class);
            if (listeners != null) {
                for (OnPhotoListener photoListener : listeners)
                    photoListener.onPhotoDeleted(photoId);
            }
        }
    }

    @Override
    public boolean uploadPhoto(byte[] imageBytes, String comment) throws IOException, JSONException {
        final RequestType requestType = RequestType.UPLOAD;
        final JSONObject jsonObject = createJsonObject(imageBytes, comment);
        StorePhotoAsyncTask task = new StorePhotoAsyncTask(installationId, photoStreamUrl, new StorePhotoAsyncTask.OnPhotoStoredCallback() {
            @Override
            public void onPhotoStoreSuccess(Photo photo) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                Logger.log(TAG, LogLevel.INFO, "onPhotoStoreSuccess()");
                notifyPhotoUploadSucceded(photo);
                onNewPhoto(photo);
            }

            @Override
            public void onPhotoStoreError(HttpResult httpResult) {
                removeOpenRequest(requestType);
                determineShouldDismissProgressDialog(requestType);
                Logger.log(TAG, LogLevel.INFO, "onPhotoStoreError()");
                notifyPhotoUploadFailed(httpResult);
            }

        });
        addOpenRequest(requestType);
        determineShouldShowProgressDialog(requestType);
        task.execute(jsonObject);
        return true;
    }

    private void notifyPhotoUploadFailed(HttpResult httpResult) {
        for (OnPhotoUploadListener onPhotoUploadListener : onPhotoUploadListeners)
            onPhotoUploadListener.onPhotoUploadFailed(httpResult);
    }

    private void notifyPhotoUploadSucceded(Photo photo) {
        for (OnPhotoUploadListener onPhotoUploadListener : onPhotoUploadListeners)
            onPhotoUploadListener.onPhotoUploaded(photo);
    }

    private JSONObject createJsonObject(byte[] imageBytes, String comment) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("image", Base64.encodeToString(imageBytes, 0, imageBytes.length, Base64.DEFAULT));
        jsonObject.put("comment", comment);
        return jsonObject;
    }

    @Override
    public void onNewPhoto(Photo photo) {
        try {
            photo.saveToImageToCache(context);
            notifyOnNewPhoto(photo);
        } catch (IOException e) {
            Logger.log(TAG, LogLevel.ERROR, e.toString());
        }
    }

    @Override
    public void onNewComment(Comment comment) {
        notifyOnNewComment(comment);
    }

    @Override
    public void onCommentDeleted(int commentId) {
        notifyOnCommentDeleted(commentId);
    }

    @Override
    public void onPhotoDeleted(int photoId) {
        notifyOnPhotoDeleted(photoId);
    }

}
