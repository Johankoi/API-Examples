package io.agora.api.example.examples.advanced.beauty.aync;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import io.agora.base.TextureBufferHelper;
import io.agora.base.VideoFrame;
import io.agora.base.internal.video.GlRectDrawer;
import io.agora.base.internal.video.GlTextureFrameBuffer;
import io.agora.base.internal.video.GlUtil;

public abstract class BaseBeautyAsync {
    protected final String TAG = this.getClass().getSimpleName();

    private final BeautyFrameProducer producer;
    private final BeautyFrameConsumer consumer;

    private GlTextureFrameBuffer outFrameBuffer;
    private volatile boolean outFrameUpdate = false;
    private final GlRectDrawer mDrawer = new GlRectDrawer();

    private final TextureBufferHelper textureBufferHelper;

    private volatile boolean isRelease = false;

    private volatile boolean isFront = false;

    private volatile int skipFrame = 0;

    public BaseBeautyAsync(TextureBufferHelper textureBufferHelper) {
        this.textureBufferHelper = textureBufferHelper;
        consumer = new BeautyFrameConsumer(textureBufferHelper, this::onFrameConsumed);
        producer = new BeautyFrameProducer(textureBufferHelper.getEglBase().getEglBaseContext(), this::onFrameProduced);
    }


    public boolean process(VideoFrame videoFrame) {
        if (isRelease) {
            Log.e(TAG, "Process -- The BeautyAsync has been released.");
            return false;
        }
        VideoFrame.Buffer buffer = videoFrame.getBuffer();
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        boolean isFront = videoFrame.getSourceType() == VideoFrame.SourceType.kFrontCamera;
        if (buffer instanceof VideoFrame.TextureBuffer) {
            VideoFrame.TextureBuffer textureBuffer = (VideoFrame.TextureBuffer) buffer;
            long startTime = System.currentTimeMillis();
            producer.pushFrameSync(
                    textureBuffer.getTextureId(),
                    textureBuffer.getType() == VideoFrame.TextureBuffer.Type.OES ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D,
                    width,
                    height,
                    isFront
            );
            Log.d(TAG, "Producer pushFrameSync cost " + (System.currentTimeMillis() - startTime));

            if(isFront != this.isFront){
                this.isFront = isFront;
                skipFrame = 2;
                return false;
            }
            if(skipFrame > 0){
                skipFrame --;
                return false;
            }
            if (outFrameBuffer != null && outFrameUpdate) {
                VideoFrame.TextureBuffer newBuffer = textureBufferHelper.wrapTextureBuffer(width, height, VideoFrame.TextureBuffer.Type.RGB, outFrameBuffer.getTextureId(), textureBuffer.getTransformMatrix());
                videoFrame.replaceBuffer(newBuffer, videoFrame.getRotation(), videoFrame.getTimestampNs());
                outFrameUpdate = false;
                return true;
            }
        } else {
            Log.e(TAG, "The buffer of videoFrame is not TextureBuffer");
        }
        return false;
    }

    public void release() {
        isRelease = true;
        long startTime = System.currentTimeMillis();
        producer.release();
        Log.d(TAG, "Producer release cost " + (System.currentTimeMillis() - startTime));
        textureBufferHelper.invoke(() -> {
            if (outFrameBuffer != null) {
                outFrameBuffer.release();
                outFrameBuffer = null;
            }
            return null;
        });
        Log.d(TAG, "All release cost " + (System.currentTimeMillis() - startTime));
    }

    private void onFrameConsumed(AsyncVideoFrame videoFrame) {
        if (isRelease) {
            return;
        }
        if(videoFrame.isFront != this.isFront){
            return;
        }
        int width = videoFrame.width;
        int height = videoFrame.height;
        int originTexId = videoFrame.textureId;
        long startTime = System.currentTimeMillis();
        int retTextId = process(videoFrame, width, height, originTexId);
        Log.d(TAG, "onFrameConsumed beauty process cost " + (System.currentTimeMillis() - startTime));


        // copy to fbo textureId
        if (outFrameBuffer == null) {
            outFrameBuffer = new GlTextureFrameBuffer(GLES20.GL_RGBA);
        }
        outFrameBuffer.setSize(width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFrameBuffer.getFrameBufferId());
        mDrawer.drawRgb(retTextId, GlUtil.IDENTITY_MATRIX, width, height, 0, 0, width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glFinish();
        outFrameUpdate = true;
    }

    protected abstract int process(AsyncVideoFrame videoFrame, int width, int height, int originTexId);

    private void onFrameProduced(int index) {
        if (isRelease) {
            Log.e(TAG, "onFrameProduced -- The BeautyAsync has been released.");
            return;
        }
        consumer.consume(producer, index);
    }
}
