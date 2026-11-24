package com.limelight.binding.video;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Thread-safe double-buffered mailbox for handing off RGBA transport-bar frames
 * from the UI thread to the OpenXR GL render thread without dropping newer frames
 * or racing with {@code glTexSubImage2D}.
 *
 * <p>Ownership invariant:
 * <ul>
 *   <li>{@link #post(ByteBuffer)} copies incoming pixels into {@code staging} under the lock
 *       and marks {@code dirty = true}. The caller may immediately reuse or mutate its source
 *       buffer after {@code post} returns.</li>
 *   <li>{@link #pollForUpload()} is called sequentially on the GL render thread. When
 *       {@code dirty} is true, it swaps {@code staging} and {@code upload} under the lock
 *       and returns {@code upload}. Because {@code post} only writes to {@code staging},
 *       the returned {@code upload} buffer is exclusively owned by the GL thread until the
 *       next {@code pollForUpload()} call.</li>
 * </ul>
 */
public final class TransportBufferQueue {
    private final int byteCapacity;
    private final Object lock = new Object();
    private ByteBuffer staging;
    private ByteBuffer upload;
    private boolean dirty;

    public TransportBufferQueue(int byteCapacity) {
        if (byteCapacity <= 0) {
            throw new IllegalArgumentException("byteCapacity must be positive: " + byteCapacity);
        }
        this.byteCapacity = byteCapacity;
        this.staging = ByteBuffer.allocateDirect(byteCapacity).order(ByteOrder.nativeOrder());
        this.upload = ByteBuffer.allocateDirect(byteCapacity).order(ByteOrder.nativeOrder());
    }

    public int getByteCapacity() {
        return byteCapacity;
    }

    /**
     * Copies {@code byteCapacity} bytes from {@code pixels} (starting at its current position)
     * into the staging buffer. Never drops the latest update and never mutates the caller's
     * buffer position/limit.
     */
    public boolean post(ByteBuffer pixels) {
        if (pixels == null || pixels.remaining() < byteCapacity) {
            return false;
        }
        ByteBuffer slice = pixels.duplicate();
        slice.limit(slice.position() + byteCapacity);
        synchronized (lock) {
            staging.clear();
            staging.put(slice);
            staging.flip();
            dirty = true;
        }
        return true;
    }

    /**
     * Called on the GL render thread to obtain the latest posted frame for upload, or
     * {@code null} if nothing new was posted since the last poll.
     */
    public ByteBuffer pollForUpload() {
        synchronized (lock) {
            if (!dirty) {
                return null;
            }
            ByteBuffer swapped = staging;
            staging = upload;
            upload = swapped;
            dirty = false;
            upload.rewind();
            return upload;
        }
    }

    public void clear() {
        synchronized (lock) {
            dirty = false;
        }
    }
}
