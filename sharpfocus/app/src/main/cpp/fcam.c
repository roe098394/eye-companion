// Tiny V4L2 camera reader for the projector's built-in focus camera.
// Android's camera service is switched off on this projector, so we talk to /dev/videoN directly.
// Runs inside the root helper (app_process), loaded with System.load().
//
// Gives back one grey (luma) frame at a time. If the camera only offers MJPEG, the raw
// JPEG bytes are returned instead and Kotlin decodes them.

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <linux/videodev2.h>

#define NBUF 4

typedef struct {
    int fd;
    unsigned int w, h, fourcc, bytesperline;
    void *start[NBUF];
    size_t len[NBUF];
    int nbuf;
    int streaming;
} Cam;

static int xioctl(int fd, unsigned long req, void *arg) {
    int r;
    do { r = ioctl(fd, req, arg); } while (r == -1 && errno == EINTR);
    return r;
}

static char g_err[256];
static void seterr(const char *what) { snprintf(g_err, sizeof g_err, "%s: %s", what, strerror(errno)); }

static int try_fmt(int fd, unsigned int fourcc, unsigned int w, unsigned int h, struct v4l2_format *out) {
    struct v4l2_format f;
    memset(&f, 0, sizeof f);
    f.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    f.fmt.pix.width = w;
    f.fmt.pix.height = h;
    f.fmt.pix.pixelformat = fourcc;
    f.fmt.pix.field = V4L2_FIELD_ANY;
    if (xioctl(fd, VIDIOC_S_FMT, &f) < 0) return -1;
    if (f.fmt.pix.pixelformat != fourcc) return -1;
    *out = f;
    return 0;
}

JNIEXPORT jstring JNICALL Java_com_sharpfocus_Cam_lastError(JNIEnv *env, jclass c) {
    return (*env)->NewStringUTF(env, g_err);
}

/** Describe a device: driver, card name, whether it captures, and its formats/sizes. */
JNIEXPORT jstring JNICALL Java_com_sharpfocus_Cam_info(JNIEnv *env, jclass c, jstring jdev) {
    const char *dev = (*env)->GetStringUTFChars(env, jdev, 0);
    char out[4096]; int n = 0;
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        n += snprintf(out + n, sizeof out - n, "%s: can't open (%s)", dev, strerror(errno));
    } else {
        struct v4l2_capability cap; memset(&cap, 0, sizeof cap);
        if (xioctl(fd, VIDIOC_QUERYCAP, &cap) == 0) {
            unsigned int caps = (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
            n += snprintf(out + n, sizeof out - n, "%s: %s / %s capture=%d stream=%d\n", dev,
                          cap.driver, cap.card, !!(caps & V4L2_CAP_VIDEO_CAPTURE), !!(caps & V4L2_CAP_STREAMING));
        }
        struct v4l2_fmtdesc fd2; memset(&fd2, 0, sizeof fd2);
        fd2.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        for (fd2.index = 0; xioctl(fd, VIDIOC_ENUM_FMT, &fd2) == 0 && n < 3800; fd2.index++) {
            unsigned int f = fd2.pixelformat;
            n += snprintf(out + n, sizeof out - n, "  fmt %c%c%c%c:", f & 255, (f >> 8) & 255, (f >> 16) & 255, (f >> 24) & 255);
            struct v4l2_frmsizeenum fs; memset(&fs, 0, sizeof fs);
            fs.pixel_format = f;
            for (fs.index = 0; xioctl(fd, VIDIOC_ENUM_FRAMESIZES, &fs) == 0 && fs.index < 12 && n < 3800; fs.index++) {
                if (fs.type == V4L2_FRMSIZE_TYPE_DISCRETE)
                    n += snprintf(out + n, sizeof out - n, " %ux%u", fs.discrete.width, fs.discrete.height);
                else { n += snprintf(out + n, sizeof out - n, " up to %ux%u", fs.stepwise.max_width, fs.stepwise.max_height); break; }
            }
            n += snprintf(out + n, sizeof out - n, "\n");
        }
        close(fd);
    }
    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    return (*env)->NewStringUTF(env, out);
}

/** Open and start streaming. Returns a handle (0 on failure, see lastError). */
JNIEXPORT jlong JNICALL Java_com_sharpfocus_Cam_open(JNIEnv *env, jclass c, jstring jdev, jint w, jint h) {
    const char *dev = (*env)->GetStringUTFChars(env, jdev, 0);
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    if (fd < 0) { seterr("open"); return 0; }

    struct v4l2_format f;
    // Preferred: formats where the grey picture is easy to pull out. MJPEG last.
    unsigned int prefs[] = { V4L2_PIX_FMT_YUYV, V4L2_PIX_FMT_UYVY, V4L2_PIX_FMT_NV12, V4L2_PIX_FMT_NV21,
                             V4L2_PIX_FMT_GREY, V4L2_PIX_FMT_YUV420, V4L2_PIX_FMT_MJPEG };
    int ok = 0;
    for (unsigned i = 0; i < sizeof prefs / sizeof prefs[0] && !ok; i++)
        if (try_fmt(fd, prefs[i], (unsigned) w, (unsigned) h, &f) == 0) ok = 1;
    if (!ok) { seterr("no usable format"); close(fd); return 0; }

    Cam *cam = calloc(1, sizeof(Cam));
    cam->fd = fd;
    cam->w = f.fmt.pix.width; cam->h = f.fmt.pix.height;
    cam->fourcc = f.fmt.pix.pixelformat; cam->bytesperline = f.fmt.pix.bytesperline;

    struct v4l2_requestbuffers req; memset(&req, 0, sizeof req);
    req.count = NBUF; req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 2) { seterr("REQBUFS"); close(fd); free(cam); return 0; }
    cam->nbuf = (int) req.count > NBUF ? NBUF : (int) req.count;
    for (int i = 0; i < cam->nbuf; i++) {
        struct v4l2_buffer b; memset(&b, 0, sizeof b);
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; b.memory = V4L2_MEMORY_MMAP; b.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &b) < 0) { seterr("QUERYBUF"); close(fd); free(cam); return 0; }
        cam->len[i] = b.length;
        cam->start[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        if (cam->start[i] == MAP_FAILED) { seterr("mmap"); close(fd); free(cam); return 0; }
        if (xioctl(fd, VIDIOC_QBUF, &b) < 0) { seterr("QBUF"); close(fd); free(cam); return 0; }
    }
    enum v4l2_buf_type t = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &t) < 0) { seterr("STREAMON"); close(fd); free(cam); return 0; }
    cam->streaming = 1;
    return (jlong) (intptr_t) cam;
}

JNIEXPORT jint JNICALL Java_com_sharpfocus_Cam_width(JNIEnv *e, jclass c, jlong hnd) { return ((Cam *) (intptr_t) hnd)->w; }
JNIEXPORT jint JNICALL Java_com_sharpfocus_Cam_height(JNIEnv *e, jclass c, jlong hnd) { return ((Cam *) (intptr_t) hnd)->h; }
JNIEXPORT jint JNICALL Java_com_sharpfocus_Cam_fourcc(JNIEnv *e, jclass c, jlong hnd) { return (jint) ((Cam *) (intptr_t) hnd)->fourcc; }

/** Wait for one frame; returns 0 and the buffer index in *idx, or -1. */
static int dq(Cam *cam, struct v4l2_buffer *b) {
    for (int tries = 0; tries < 50; tries++) {
        fd_set fds; FD_ZERO(&fds); FD_SET(cam->fd, &fds);
        struct timeval tv = { 2, 0 };
        int r = select(cam->fd + 1, &fds, NULL, NULL, &tv);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) { errno = ETIMEDOUT; return -1; }
        memset(b, 0, sizeof *b);
        b->type = V4L2_BUF_TYPE_VIDEO_CAPTURE; b->memory = V4L2_MEMORY_MMAP;
        if (xioctl(cam->fd, VIDIOC_DQBUF, b) == 0) return 0;
        if (errno != EAGAIN) return -1;
    }
    errno = ETIMEDOUT;
    return -1;
}

/**
 * Throw away `skip` frames (so the picture is fresh after the lens moved), then return the next one:
 * a w*h grey image, or (MJPEG) the JPEG bytes.
 */
JNIEXPORT jbyteArray JNICALL Java_com_sharpfocus_Cam_grab(JNIEnv *env, jclass c, jlong hnd, jint skip) {
    Cam *cam = (Cam *) (intptr_t) hnd;
    struct v4l2_buffer b;
    // throw away every frame already waiting (they were taken before the lens finished moving)
    for (int k = 0; k < 16; k++) {
        memset(&b, 0, sizeof b);
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; b.memory = V4L2_MEMORY_MMAP;
        if (xioctl(cam->fd, VIDIOC_DQBUF, &b) < 0) break;   // EAGAIN: nothing left waiting
        xioctl(cam->fd, VIDIOC_QBUF, &b);
    }
    for (int i = 0; i < skip; i++) {
        if (dq(cam, &b) < 0) { seterr("DQBUF"); return NULL; }
        xioctl(cam->fd, VIDIOC_QBUF, &b);
    }
    if (dq(cam, &b) < 0) { seterr("DQBUF"); return NULL; }
    unsigned char *src = cam->start[b.index];
    jbyteArray out;
    unsigned int w = cam->w, h = cam->h, bpl = cam->bytesperline ? cam->bytesperline : w * 2;
    if (cam->fourcc == V4L2_PIX_FMT_MJPEG) {
        out = (*env)->NewByteArray(env, (jsize) b.bytesused);
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) b.bytesused, (jbyte *) src);
    } else {
        unsigned char *y = malloc((size_t) w * h);
        if (cam->fourcc == V4L2_PIX_FMT_YUYV) {
            for (unsigned r = 0; r < h; r++) { unsigned char *s = src + r * bpl; for (unsigned x = 0; x < w; x++) y[r * w + x] = s[x * 2]; }
        } else if (cam->fourcc == V4L2_PIX_FMT_UYVY) {
            for (unsigned r = 0; r < h; r++) { unsigned char *s = src + r * bpl; for (unsigned x = 0; x < w; x++) y[r * w + x] = s[x * 2 + 1]; }
        } else {
            // NV12 / NV21 / YUV420 / GREY: the first w*h bytes (per row) are the grey picture
            unsigned int stride = cam->bytesperline ? cam->bytesperline : w;
            for (unsigned r = 0; r < h; r++) memcpy(y + r * w, src + r * stride, w);
        }
        out = (*env)->NewByteArray(env, (jsize) (w * h));
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) (w * h), (jbyte *) y);
        free(y);
    }
    xioctl(cam->fd, VIDIOC_QBUF, &b);
    return out;
}

/** Set a camera control (e.g. turn off auto exposure). Returns 0 on success. */
JNIEXPORT jint JNICALL Java_com_sharpfocus_Cam_setControl(JNIEnv *env, jclass c, jlong hnd, jint id, jint value) {
    Cam *cam = (Cam *) (intptr_t) hnd;
    struct v4l2_control ctl = { (unsigned) id, value };
    return xioctl(cam->fd, VIDIOC_S_CTRL, &ctl) == 0 ? 0 : -errno;
}

JNIEXPORT void JNICALL Java_com_sharpfocus_Cam_close(JNIEnv *env, jclass c, jlong hnd) {
    Cam *cam = (Cam *) (intptr_t) hnd;
    if (!cam) return;
    if (cam->streaming) { enum v4l2_buf_type t = V4L2_BUF_TYPE_VIDEO_CAPTURE; xioctl(cam->fd, VIDIOC_STREAMOFF, &t); }
    for (int i = 0; i < cam->nbuf; i++) if (cam->start[i] && cam->start[i] != MAP_FAILED) munmap(cam->start[i], cam->len[i]);
    close(cam->fd);
    free(cam);
}
