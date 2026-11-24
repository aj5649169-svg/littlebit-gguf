// JNI bridge between Kotlin (com.littlebit.chat.engine.LlamaEngine) and the
// repository's patched llama.cpp (LittleBit Q1_0 + Arm KleidiAI).
//
// Built for arm64-v8a only, with GGML_NATIVE=OFF / GGML_CPU_KLEIDIAI=ON /
// GGML_OPENMP=OFF — matching scripts/build-android.sh so in-app inference
// runs at Termux-binary speed without shelling out.

#include <jni.h>
#include <android/log.h>

#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "LittleBitJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM * g_vm = nullptr;
std::once_flag g_init_flag;

// Cached classes / method IDs for the Kotlin callback interfaces
// (com.littlebit.chat.engine.LlamaEngine$ProgressCallback / $TokenCallback).
jclass    g_progress_cb_class    = nullptr;
jmethodID g_progress_on_progress = nullptr;
jclass    g_token_cb_class       = nullptr;
jmethodID g_token_on_token       = nullptr;
jmethodID g_token_on_complete    = nullptr;
jmethodID g_token_on_error       = nullptr;

struct NativeEngine {
    llama_model *       model  = nullptr;
    llama_context *     ctx    = nullptr;
    const llama_vocab * vocab  = nullptr;
    std::atomic<bool>   stop{false};
    // Number of tokens currently decoded in the KV cache. Tracked across
    // nativeGenerate / nativeContinueGenerate so a speculative pre-warm can
    // be flushed and seamlessly continued without re-processing the prompt.
    int                 n_past = 0;
};

void throwIllegalState(JNIEnv * env, const std::string & msg) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (ex != nullptr) {
        env->ThrowNew(ex, msg.c_str());
        env->DeleteLocalRef(ex);
    }
}

std::string toStdString(JNIEnv * env, jstring s) {
    if (s == nullptr) return std::string();
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

void logToAndroid(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level >= GGML_LOG_LEVEL_ERROR && text != nullptr) {
        LOGE("%s", text);
    }
}

jmethodID getMethod(JNIEnv * env, jclass clazz, const char * name, const char * sig) {
    jmethodID mid = env->GetMethodID(clazz, name, sig);
    if (mid == nullptr) {
        throw std::runtime_error(std::string("callback method not found: ") + name + sig);
    }
    return mid;
}

struct ProgressState {
    JNIEnv * env;
    jobject  cb;
};

bool progressThunk(float progress, void * user_data) {
    auto * st = static_cast<ProgressState *>(user_data);
    if (st != nullptr && st->cb != nullptr && g_progress_on_progress != nullptr) {
        st->env->CallVoidMethod(st->cb, g_progress_on_progress, progress);
        if (st->env->ExceptionCheck()) { st->env->ExceptionClear(); return false; }
    }
    return true;
}

std::string tokenToPiece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int len = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (len >= 0) return std::string(buf, buf + len);
    std::vector<char> big(static_cast<size_t>(-len) + 1);
    len = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
    if (len >= 0) return std::string(big.data(), big.data() + len);
    return std::string();
}


/** Decode ordinary UTF-8 token bytes to UTF-16; replace malformed sequences. */
jstring utf8ToJString(JNIEnv * env, const std::string & input) {
    std::vector<jchar> out;
    out.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        const auto a = static_cast<unsigned char>(input[i]);
        uint32_t cp = 0xFFFD;
        size_t n = 1;
        if (a <= 0x7F) cp = a;
        else if (a >= 0xC2 && a <= 0xDF && i + 1 < input.size()) {
            const auto b = static_cast<unsigned char>(input[i + 1]);
            if ((b & 0xC0) == 0x80) { cp = ((a & 0x1F) << 6) | (b & 0x3F); n = 2; }
        } else if (a >= 0xE0 && a <= 0xEF && i + 2 < input.size()) {
            const auto b = static_cast<unsigned char>(input[i + 1]);
            const auto c = static_cast<unsigned char>(input[i + 2]);
            if ((b & 0xC0) == 0x80 && (c & 0xC0) == 0x80
                && !(a == 0xE0 && b < 0xA0) && !(a == 0xED && b >= 0xA0)) {
                cp = ((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F); n = 3;
            }
        } else if (a >= 0xF0 && a <= 0xF4 && i + 3 < input.size()) {
            const auto b = static_cast<unsigned char>(input[i + 1]);
            const auto c = static_cast<unsigned char>(input[i + 2]);
            const auto d = static_cast<unsigned char>(input[i + 3]);
            if ((b & 0xC0) == 0x80 && (c & 0xC0) == 0x80 && (d & 0xC0) == 0x80
                && !(a == 0xF0 && b < 0x90) && !(a == 0xF4 && b > 0x8F)) {
                cp = ((a & 0x07) << 18) | ((b & 0x3F) << 12) | ((c & 0x3F) << 6) | (d & 0x3F); n = 4;
            }
        }
        i += n;
        if (cp <= 0xFFFF) out.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

void callOnError(JNIEnv * env, jobject cb, const char * msg) {
    if (g_token_on_error == nullptr) return;
    jstring jmsg = utf8ToJString(env, std::string(msg));
    if (jmsg == nullptr) { if (env->ExceptionCheck()) env->ExceptionClear(); return; }
    env->CallVoidMethod(cb, g_token_on_error, jmsg);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jmsg);
}

struct SamplerOpts {
    double temp;
    int    top_k;
    double repeat_penalty;
    double dry_multiplier;
    long   seed;
};

// Sampler chain: penalties -> DRY (optional) -> top-k -> temperature -> dist/greedy.
llama_sampler * buildSampler(const llama_vocab * vocab, int n_vocab, const SamplerOpts & o) {
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            n_vocab, 64, static_cast<float>(o.repeat_penalty), 0.0f, 0.0f));
    if (o.dry_multiplier > 0.0) {
        static const char * breakers[] = {"\n", ":", "\"", "*"};
        llama_sampler_chain_add(chain, llama_sampler_init_dry(
                vocab, static_cast<float>(o.dry_multiplier), 1.75f, 2, 64, breakers, 4));
    }
    if (o.top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(o.top_k));
    }
    if (o.temp <= 0.0) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(static_cast<float>(o.temp)));
        const uint32_t useed = o.seed >= 0
            ? static_cast<uint32_t>(o.seed)
            : static_cast<uint32_t>(std::chrono::high_resolution_clock::now()
                                        .time_since_epoch().count());
        llama_sampler_chain_add(chain, llama_sampler_init_dist(useed));
    }
    return chain;
}

// Sampling loop shared by nativeGenerate and nativeContinueGenerate.
// Returns 0 finished, 1 stopped/aborted, 2 context full, -1 error.
int runSamplingLoop(JNIEnv * env, NativeEngine * e, jobject cb,
                    llama_sampler * chain, jint max_tokens) {
    const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
    const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

    const auto t0 = std::chrono::steady_clock::now();
    int n_gen = 0;
    int status = 0; // 0 = finished, 1 = stopped/aborted, 2 = context full
    std::vector<llama_token_data> candidates(static_cast<size_t>(n_vocab));

    while (!e->stop.load()) {
        if (n_gen >= max_tokens) break;
        if (e->n_past >= n_ctx - 1) {
            status = 2;
            callOnError(env, cb, "context window is full — start a new chat");
            break;
        }

        float * logits = llama_get_logits_ith(e->ctx, -1);
        if (logits == nullptr) {
            callOnError(env, cb, "no logits available");
            status = -1;
            break;
        }
        for (int v = 0; v < n_vocab; v++) {
            candidates[static_cast<size_t>(v)] = llama_token_data{v, logits[v], 0.0f};
        }
        llama_token_data_array cur{ candidates.data(), static_cast<size_t>(n_vocab), -1, false };
        llama_sampler_apply(chain, &cur);
        const llama_token tok = cur.data[cur.selected].id;
        llama_sampler_accept(chain, tok);

        if (llama_vocab_is_eog(e->vocab, tok)) break;

        const std::string piece = tokenToPiece(e->vocab, tok);
        if (!piece.empty()) {
            jstring jpiece = utf8ToJString(env, piece);
            if (jpiece == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                status = -1;
                break;
            }
            env->CallVoidMethod(cb, g_token_on_token, jpiece);
            const bool callbackFailed = env->ExceptionCheck();
            if (callbackFailed) env->ExceptionClear();
            env->DeleteLocalRef(jpiece);
            if (callbackFailed) { status = -1; break; }
        }
        n_gen++;

        llama_batch_ext * b = llama_batch_ext_init(e->ctx);
        if (b == nullptr) { status = -1; callOnError(env, cb, "could not allocate decode batch"); break; }
        const llama_pos pos = e->n_past;
        const int idx = llama_batch_ext_add_token(b, 0, tok);
        if (idx >= 0) {
            llama_batch_ext_set_pos(b, idx, &pos);
            llama_batch_ext_set_output_logits(b, idx, true);
        }
        const int rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, b);
        llama_batch_ext_free(b);
        if (idx < 0 || rc != 0) {
            status = 1;
            break;
        }
        e->n_past++;
    }

    const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    const double tps = elapsed > 0.0 ? static_cast<double>(n_gen) / elapsed : 0.0;
    env->CallVoidMethod(cb, g_token_on_complete, n_gen, tps);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return status;
}

} // namespace

jint JNI_OnLoad(JavaVM * vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeInit(JNIEnv * env, jclass) {
    std::call_once(g_init_flag, [&]() {
        llama_log_set(logToAndroid, nullptr);
        llama_backend_init();

        jclass local = env->FindClass("com/littlebit/chat/engine/LlamaEngine$ProgressCallback");
        if (local == nullptr) return;
        g_progress_cb_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);

        local = env->FindClass("com/littlebit/chat/engine/LlamaEngine$TokenCallback");
        if (local == nullptr) return;
        g_token_cb_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);

        try {
            g_progress_on_progress = getMethod(env, g_progress_cb_class, "onProgress", "(F)V");
            g_token_on_token       = getMethod(env, g_token_cb_class, "onToken", "(Ljava/lang/String;)V");
            g_token_on_complete    = getMethod(env, g_token_cb_class, "onComplete", "(ID)V");
            g_token_on_error       = getMethod(env, g_token_cb_class, "onError", "(Ljava/lang/String;)V");
        } catch (const std::exception & e) {
            LOGE("callback init failed: %s", e.what());
        }
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeLoadModel(
        JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads, jint n_batch,
        jint n_gpu_layers, jobject jprogress) {
    try {
        const std::string path = toStdString(env, jpath);
        auto * engine = new NativeEngine();

        ProgressState * pst = nullptr;
        jobject progress_ref = nullptr;
        if (jprogress != nullptr) {
            progress_ref = env->NewGlobalRef(jprogress);
            pst = new ProgressState{env, progress_ref};
        }

        llama_model_params mparams = llama_model_default_params();
        // Vulkan GPU offload lands in v0.2 (flip -DGGML_VULKAN=ON in CMake);
        // until then n_gpu_layers is plumbed through but stays 0 (CPU + KleidiAI).
        mparams.n_gpu_layers = n_gpu_layers;
        mparams.progress_callback = pst != nullptr ? progressThunk : nullptr;
        mparams.progress_callback_user_data = pst;

        engine->model = llama_model_load_from_file(path.c_str(), mparams);

        if (pst != nullptr) {
            env->DeleteGlobalRef(progress_ref);
            delete pst;
        }
        if (engine->model == nullptr) {
            delete engine;
            throw std::runtime_error("failed to load model: " + path);
        }
        engine->vocab = llama_model_get_vocab(engine->model);

        // Defaults: 4096 context / 4 threads (Big.LITTLE big cores) / 512 batch —
        // OOM-safe on 6 GB phones, matching scripts/build-android.sh guidance.
        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx           = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 4096;
        cparams.n_batch         = n_batch > 0 ? static_cast<uint32_t>(n_batch) : 512;
        cparams.n_ubatch        = cparams.n_batch;
        cparams.n_threads       = n_threads;
        cparams.n_threads_batch = n_threads;

        engine->ctx = llama_init_from_model(engine->model, cparams);
        if (engine->ctx == nullptr) {
            llama_model_free(engine->model);
            delete engine;
            throw std::runtime_error("failed to create llama context (out of memory?)");
        }
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception & e) {
        throwIllegalState(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelDesc(JNIEnv * env, jclass, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return nullptr;
    char buf[256];
    llama_model_desc(e->model, buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelSizeBytes(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return 0;
    return static_cast<jlong>(llama_model_size(e->model));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelFtype(JNIEnv * env, jclass, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return nullptr;
    return env->NewStringUTF(llama_ftype_name(llama_model_ftype(e->model)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeFormatChat(
        JNIEnv * env, jclass, jlong handle, jstring jsystem, jobjectArray jroles, jobjectArray jcontents) {
    try {
        auto * e = reinterpret_cast<NativeEngine *>(handle);
        if (e == nullptr || e->model == nullptr) throw std::runtime_error("no model loaded");

        std::deque<std::string> storage; // stable references while we build the message array
        std::vector<llama_chat_message> msgs;
        std::string system_prompt;

        if (jsystem != nullptr) {
            system_prompt = toStdString(env, jsystem);
            msgs.push_back({"system", system_prompt.c_str()});
        }
        const jsize n = env->GetArrayLength(jroles);
        for (jsize i = 0; i < n; i++) {
            auto * jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
            auto * jc = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
            storage.emplace_back(toStdString(env, jr));
            const char * role = storage.back().c_str();
            storage.emplace_back(toStdString(env, jc));
            const char * content = storage.back().c_str();
            if (jr != nullptr) env->DeleteLocalRef(jr);
            if (jc != nullptr) env->DeleteLocalRef(jc);
            msgs.push_back({role, content});
        }

        // Prefer the GGUF-embedded chat template, but NEVER let it crash a turn:
        // llama.cpp's --jinja path can throw std::out_of_range when prior
        // assistant turns contain <think> blocks (multi-turn crash on Qwen3).
        // Kotlin strips those blocks before formatting; the ChatML fallback
        // below is the second line of defence so multi-turn NEVER crashes.
        const char * tmpl = llama_model_chat_template(e->model, nullptr);
        if (tmpl != nullptr) {
            try {
                int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
                if (len < 0) throw std::runtime_error("chat template failed");
                std::vector<char> buf(static_cast<size_t>(len) + 16);
                len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                                buf.data(), static_cast<int32_t>(buf.size()));
                if (len < 0) throw std::runtime_error("chat template failed");
                return env->NewStringUTF(buf.data());
            } catch (const std::exception & ex) {
                LOGE("chat template failed (%s); falling back to ChatML", ex.what());
            }
        }

        // Safe ChatML fallback: <|im_start|>role\ncontent<|im_end|>\n ...
        std::string out;
        for (const auto & m : msgs) {
            out += "<|im_start|>" + std::string(m.role) + "\n" + m.content + "<|im_end|>\n";
        }
        out += "<|im_start|>assistant\n";
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception & e) {
        throwIllegalState(env, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt,
        jdouble temp, jint top_k, jdouble repeat_penalty, jdouble dry_multiplier,
        jint max_tokens, jlong seed, jobject jcallback) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->ctx == nullptr) {
        throwIllegalState(env, "no model loaded");
        return -1;
    }
    if (jcallback == nullptr) {
        throwIllegalState(env, "callback is null");
        return -1;
    }
    jobject cb = env->NewGlobalRef(jcallback);
    if (cb == nullptr) { if (env->ExceptionCheck()) env->ExceptionClear(); throwIllegalState(env, "could not retain generation callback"); return -1; }

    try {
        e->stop.store(false);
        const std::string prompt = toStdString(env, jprompt);
        const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
        const int n_batch = static_cast<int>(llama_n_batch(e->ctx));
        const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

        // Hermetic turns: clear the KV cache so every generate() call decodes
        // the full templated prompt from position 0. This makes repeat calls
        // (retries, pre-warm flushes that fell back) deterministic and keeps
        // llama.cpp happy even after a speculative pre-warm warmed the cache.
        // NOTE: llama_kv_cache_clear() was removed upstream in favour of the
        // llama_memory_* API (llama.h "Memory" section at this pinned commit).
        llama_memory_clear(llama_get_memory(e->ctx), true);
        e->n_past = 0;

        // Tokenize the fully-templated prompt (special tokens parsed, BOS left to the template).
        std::vector<llama_token> prompt_tokens(prompt.size() + 8);
        int n_prompt = llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                      prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                                      false, true);
        if (n_prompt < 0) {
            prompt_tokens.resize(static_cast<size_t>(-n_prompt));
            n_prompt = llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                      prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                                      false, true);
        }
        if (n_prompt <= 0) {
            callOnError(env, cb, "failed to tokenize prompt");
            env->DeleteGlobalRef(cb);
            return -1;
        }
        prompt_tokens.resize(static_cast<size_t>(n_prompt));
        if (n_prompt > n_ctx - 4) {
            callOnError(env, cb, "prompt is too long for the configured context size");
            env->DeleteGlobalRef(cb);
            return -1;
        }

        SamplerOpts opts;
        opts.temp           = temp;
        opts.top_k          = top_k;
        opts.repeat_penalty = repeat_penalty;
        opts.dry_multiplier = dry_multiplier;
        opts.seed           = static_cast<long>(seed);
        llama_sampler * chain = buildSampler(e->vocab, n_vocab, opts);
        if (chain == nullptr) { env->DeleteGlobalRef(cb); throwIllegalState(env, "could not create sampler"); return -1; }

        bool failed = false;

        // Feed the prompt in chunks of n_batch.
        for (int i0 = 0; i0 < n_prompt && !e->stop.load(); i0 += n_batch) {
            const int chunk = std::min(n_batch, n_prompt - i0);
            llama_batch_ext * b = llama_batch_ext_init(e->ctx);
            if (b == nullptr) { callOnError(env, cb, "could not allocate prompt batch"); failed = true; break; }
            for (int i = 0; i < chunk; i++) {
                const llama_pos pos = e->n_past + i;
                const int idx = llama_batch_ext_add_token(b, 0, prompt_tokens[static_cast<size_t>(i0 + i)]);
                if (idx < 0) { failed = true; break; }
                llama_batch_ext_set_pos(b, idx, &pos);
                if (i == chunk - 1) llama_batch_ext_set_output_logits(b, idx, true);
            }
            if (failed) {
                llama_batch_ext_free(b);
                callOnError(env, cb, "failed to build decode batch");
                break;
            }
            const int rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, b);
            llama_batch_ext_free(b);
            if (rc != 0) {
                callOnError(env, cb, "prompt processing failed (out of memory or aborted)");
                failed = true;
                break;
            }
            e->n_past += chunk;
        }

        int status = 0;
        if (!failed && !e->stop.load()) {
            status = runSamplingLoop(env, e, cb, chain, max_tokens);
        }
        if (chain != nullptr) { llama_sampler_free(chain); chain = nullptr; }

        env->DeleteGlobalRef(cb);

        if (failed) return -1;
        if (e->stop.load()) return 1;
        return status;
    } catch (const std::exception & ex) {
        callOnError(env, cb, ex.what());
        env->DeleteGlobalRef(cb);
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeContinueGenerate(
        JNIEnv * env, jclass, jlong handle,
        jdouble temp, jint top_k, jdouble repeat_penalty, jdouble dry_multiplier,
        jint max_tokens, jlong seed, jobject jcallback) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->ctx == nullptr) {
        throwIllegalState(env, "no model loaded");
        return -1;
    }
    if (jcallback == nullptr) {
        throwIllegalState(env, "callback is null");
        return -1;
    }
    jobject cb = env->NewGlobalRef(jcallback);

    // Continue sampling from the current KV state (speculative pre-warm flush):
    // no prompt re-processing, logits of the last decoded token are reused.
    try {
        e->stop.store(false);
        const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

        SamplerOpts opts;
        opts.temp           = temp;
        opts.top_k          = top_k;
        opts.repeat_penalty = repeat_penalty;
        opts.dry_multiplier = dry_multiplier;
        opts.seed           = static_cast<long>(seed);
        llama_sampler * chain = buildSampler(e->vocab, n_vocab, opts);
        if (chain == nullptr) { env->DeleteGlobalRef(cb); throwIllegalState(env, "could not create sampler"); return -1; }

        const int status = runSamplingLoop(env, e, cb, chain, max_tokens);
        if (chain != nullptr) { llama_sampler_free(chain); chain = nullptr; }
        env->DeleteGlobalRef(cb);

        if (e->stop.load()) return 1;
        return status;
    } catch (const std::exception & ex) {
        callOnError(env, cb, ex.what());
        env->DeleteGlobalRef(cb);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeStop(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e != nullptr) e->stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeSetThreads(JNIEnv *, jclass, jlong handle, jint threads) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e != nullptr && e->ctx != nullptr && threads > 0) {
        llama_set_n_threads(e->ctx, threads, threads);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeUnload(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr) return;
    if (e->ctx != nullptr) llama_free(e->ctx);
    if (e->model != nullptr) llama_model_free(e->model);
    delete e;
}
