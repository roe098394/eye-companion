// Small JNI bridge between the Kotlin app and llama.cpp.
// One session = one loaded model + context. All calls come from a single worker thread.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include <ctime>
#include "llama.h"

#define TAG "CompanionLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    std::string tmpl;
    std::vector<llama_token> cached;  // tokens currently held in the KV cache
};

bool g_backend_ready = false;

// Keep the last few warnings/errors from llama.cpp so the app can show why loading failed.
std::string g_last_errors;
int g_last_level = 0;
void log_cb(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_CONT) level = (ggml_log_level) g_last_level;
    g_last_level = level;
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "llama", "%s", text);
        g_last_errors += text;
        if (g_last_errors.size() > 600) g_last_errors.erase(0, g_last_errors.size() - 600);
    }
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Build a java.lang.String from raw UTF-8 bytes (NewStringUTF chokes on 4-byte chars like emoji).
jstring utf8ToJava(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = (jstring) env->NewObject(strCls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strCls);
    return out;
}

// Length of the longest prefix of s that ends on a complete UTF-8 character.
size_t completeUtf8Prefix(const std::string &s) {
    size_t pos = 0, lastGood = 0;
    while (pos < s.size()) {
        unsigned char c = (unsigned char) s[pos];
        size_t len = 1;
        if (c >= 0xF0) len = 4;
        else if (c >= 0xE0) len = 3;
        else if (c >= 0xC0) len = 2;
        if (pos + len > s.size()) break;
        pos += len;
        lastGood = pos;
    }
    return lastGood;
}

bool decodeTokens(Session *s, std::vector<llama_token> &toks, size_t from) {
    const int nBatch = (int) llama_n_batch(s->ctx);
    for (size_t i = from; i < toks.size(); i += nBatch) {
        int n = (int) std::min<size_t>(nBatch, toks.size() - i);
        llama_batch batch = llama_batch_get_one(toks.data() + i, n);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("llama_decode failed at %zu", i);
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_eyecompanion_Brain_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nThreads, jboolean useMmap) {
    g_last_errors.clear();
    if (!g_backend_ready) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }
    std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = useMmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("could not load model %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 256;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("could not create context");
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    const char *t = llama_model_chat_template(model, nullptr);
    s->tmpl = t ? t : "chatml";

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    s->sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(s->sampler, llama_sampler_init_penalties(
            llama_vocab_n_tokens(s->vocab), 64, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(s->sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(s->sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(s->sampler, llama_sampler_init_temp(0.5f));
    llama_sampler_chain_add(s->sampler, llama_sampler_init_dist((uint32_t) time(nullptr)));

    LOGI("model loaded, ctx=%u", llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jboolean JNICALL
Java_com_eyecompanion_Brain_nativeChat(JNIEnv *env, jobject, jlong handle,
                                       jobjectArray jroles, jobjectArray jcontents,
                                       jint maxTokens, jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return JNI_FALSE;

    jclass lCls = env->GetObjectClass(listener);
    jmethodID onToken = env->GetMethodID(lCls, "onToken", "(Ljava/lang/String;)Z");

    // 1. Format the conversation with the model's own chat template.
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles[i] = jstr(env, r);
        contents[i] = jstr(env, c);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    for (jsize i = 0; i < n; i++) msgs[i] = {roles[i].c_str(), contents[i].c_str()};

    std::vector<char> buf(4096);
    int len = llama_chat_apply_template(s->tmpl.c_str(), msgs.data(), msgs.size(), true,
                                        buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(s->tmpl.c_str(), msgs.data(), msgs.size(), true,
                                        buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGE("chat template failed");
        return JNI_FALSE;
    }
    std::string prompt(buf.data(), len);

    // 2. Tokenize.
    int nTok = -llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(nTok);
    if (llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), nTok, true, true) < 0) {
        LOGE("tokenize failed");
        return JNI_FALSE;
    }

    const int nCtx = (int) llama_n_ctx(s->ctx);
    if ((int) toks.size() + maxTokens >= nCtx) {
        LOGE("prompt too long (%zu tokens)", toks.size());
        return JNI_FALSE;
    }

    // 3. Reuse whatever part of the KV cache matches the new prompt.
    size_t keep = 0;
    while (keep < s->cached.size() && keep < toks.size() && s->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size() && keep > 0) keep--;  // must decode at least one token for fresh logits

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    s->cached.assign(toks.begin(), toks.begin() + keep);

    if (!decodeTokens(s, toks, keep)) {
        llama_memory_clear(mem, true);
        s->cached.clear();
        return JNI_FALSE;
    }
    s->cached = toks;

    // 4. Generate, streaming complete UTF-8 chunks back to Kotlin.
    std::string pending;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;

        int pl = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
        if (pl > 0) pending.append(piece, pl);

        size_t ok = completeUtf8Prefix(pending);
        if (ok > 0) {
            jstring js = utf8ToJava(env, pending.substr(0, ok));
            pending.erase(0, ok);
            jboolean more = env->CallBooleanMethod(listener, onToken, js);
            env->DeleteLocalRef(js);
            if (!more) break;
        }

        s->cached.push_back(tok);
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("decode failed during generation");
            break;
        }
    }
    llama_sampler_reset(s->sampler);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_eyecompanion_Brain_nativeLastError(JNIEnv *env, jobject) {
    return utf8ToJava(env, g_last_errors);
}

JNIEXPORT void JNICALL
Java_com_eyecompanion_Brain_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

} // extern "C"
