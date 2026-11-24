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
    llama_model *       model = nullptr;
    llama_context *     ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool>   stop{false};
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
        if (st->env->ExceptionCheck()) return false;
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
        JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads, jint n_batch, jobject jprogress) {
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
        mparams.n_gpu_layers = 0; // CPU-only inference, same as the Termux build (-ngl 0)
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

        const char * tmpl = llama_model_chat_template(e->model, nullptr);
        if (tmpl != nullptr) {
            int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
            if (len < 0) throw std::runtime_error("chat template failed");
            std::vector<char> buf(static_cast<size_t>(len) + 16);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            buf.data(), static_cast<int32_t>(buf.size()));
            if (len < 0) throw std::runtime_error("chat template failed");
            return env->NewStringUTF(buf.data());
        }

        // Fallback for models without an embedded template.
        std::string out;
        if (!system_prompt.empty()) out += system_prompt + "\n\n";
        for (const auto & m : msgs) {
            if (std::string(m.role) == "system") continue;
            out += std::string("### ") + m.role + ":\n" + m.content + "\n\n";
        }
        out += "### Assistant:\n";
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

    auto onError = [&](const char * msg) {
        if (g_token_on_error == nullptr) return;
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(cb, g_token_on_error, jmsg);
        env->DeleteLocalRef(jmsg);
    };

    try {
        e->stop.store(false);
        const std::string prompt = toStdString(env, jprompt);
        const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
        const int n_batch = static_cast<int>(llama_n_batch(e->ctx));
        const int n_vocab = llama_vocab_n_tokens(e->vocab);

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
            onError("failed to tokenize prompt");
            env->DeleteGlobalRef(cb);
            return -1;
        }
        prompt_tokens.resize(static_cast<size_t>(n_prompt));
        if (n_prompt > n_ctx - 4) {
            onError("prompt is too long for the configured context size");
            env->DeleteGlobalRef(cb);
            return -1;
        }

        // Sampler chain: penalties -> DRY (optional) -> top-k -> temperature -> dist/greedy.
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        llama_sampler * chain = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                n_vocab, 64, static_cast<float>(repeat_penalty), 0.0f, 0.0f));
        if (dry_multiplier > 0.0) {
            static const char * breakers[] = {"\n", ":", "\"", "*"};
            llama_sampler_chain_add(chain, llama_sampler_init_dry(
                    e->vocab, static_cast<float>(dry_multiplier), 1.75f, 2, 64, breakers, 4));
        }
        if (top_k > 0) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        }
        if (temp <= 0.0) {
            llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(chain, llama_sampler_init_temp(static_cast<float>(temp)));
            const uint32_t useed = seed >= 0
                ? static_cast<uint32_t>(seed)
                : static_cast<uint32_t>(std::chrono::high_resolution_clock::now()
                                            .time_since_epoch().count());
            llama_sampler_chain_add(chain, llama_sampler_init_dist(useed));
        }

        int n_past = 0;
        bool failed = false;

        // Feed the prompt in chunks of n_batch.
        for (int i0 = 0; i0 < n_prompt && !e->stop.load(); i0 += n_batch) {
            const int chunk = std::min(n_batch, n_prompt - i0);
            llama_batch_ext * b = llama_batch_ext_init(e->ctx);
            for (int i = 0; i < chunk; i++) {
                const llama_pos pos = n_past + i;
                const int idx = llama_batch_ext_add_token(b, 0, prompt_tokens[static_cast<size_t>(i0 + i)]);
                if (idx < 0) { failed = true; break; }
                llama_batch_ext_set_pos(b, idx, &pos);
                if (i == chunk - 1) llama_batch_ext_set_output_logits(b, idx, true);
            }
            if (failed) {
                llama_batch_ext_free(b);
                onError("failed to build decode batch");
                break;
            }
            const int rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, b);
            llama_batch_ext_free(b);
            if (rc != 0) {
                onError("prompt processing failed (out of memory or aborted)");
                failed = true;
                break;
            }
            n_past += chunk;
        }

        const auto t0 = std::chrono::steady_clock::now();
        int n_gen = 0;
        int status = 0; // 0 = finished, 1 = stopped/aborted, 2 = context full
        std::vector<llama_token_data> candidates(static_cast<size_t>(n_vocab));

        while (!failed && !e->stop.load()) {
            if (n_gen >= max_tokens) break;
            if (n_past >= n_ctx - 1) {
                status = 2;
                onError("context window is full — start a new chat");
                break;
            }

            float * logits = llama_get_logits_ith(e->ctx, -1);
            if (logits == nullptr) {
                onError("no logits available");
                failed = true;
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
                jstring jpiece = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(cb, g_token_on_token, jpiece);
                env->DeleteLocalRef(jpiece);
            }
            n_gen++;

            llama_batch_ext * b = llama_batch_ext_init(e->ctx);
            const llama_pos pos = n_past;
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
            n_past++;
        }

        llama_sampler_free(chain);

        const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        const double tps = elapsed > 0.0 ? static_cast<double>(n_gen) / elapsed : 0.0;
        env->CallVoidMethod(cb, g_token_on_complete, n_gen, tps);
        env->DeleteGlobalRef(cb);

        if (failed) return -1;
        if (e->stop.load()) return 1;
        return status;
    } catch (const std::exception & ex) {
        onError(ex.what());
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
