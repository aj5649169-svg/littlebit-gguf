// JNI bridge between Kotlin (com.littlebit.chat.engine.LlamaEngine) and the
// repository's patched llama.cpp (LittleBit Q1_0 + Arm KleidiAI).
//
// Built for arm64-v8a only, with GGML_NATIVE=OFF / GGML_CPU_KLEIDIAI=ON /
// GGML_OPENMP=OFF — matching scripts/build-android.sh so in-app inference
// runs at Termux-binary speed without shelling out.

#include <jni.h>
#include <android/log.h>

#include <llama.h>
#include <ggml-backend.h>
#include <ggml-cpu.h>
#include <cstdio>
#include <unistd.h>
#ifdef __ANDROID__
#include <vulkan/vulkan.h>
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <deque>
#include <mutex>
#include <memory>
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
jmethodID g_prefill = nullptr;
jmethodID g_token_on_error       = nullptr;

struct NativeEngine {
    llama_model *       model  = nullptr;
    llama_context *     ctx    = nullptr;
    const llama_vocab * vocab  = nullptr;
    std::atomic<bool>   stop{false};
    std::atomic<bool>   in_prefill{false};
    // Number of tokens currently decoded in the KV cache. Tracked across
    // nativeGenerate / nativeContinueGenerate so a speculative pre-warm can
    // be flushed and seamlessly continued without re-processing the prompt.
    int                 n_past = 0;
    std::vector<llama_token> cached_tokens;
    std::chrono::steady_clock::time_point started;
    double ttft_ms = 0, prefill_rate = 0, decode_rate = 0;
    llama_context_params params{};
    NativeEngine * draft = nullptr; // Kotlin holds both handles under the same engine mutex.
    std::atomic<bool> conserve{false};
    ggml_threadpool_t pool = nullptr;
    std::vector<jint> pool_cores;
    int pool_size = 0;
    bool owns_model = true;
    int                 gpu_layers = 0;
    // UTF-8 code points may span token-piece boundaries or a speculative
    // generate/continue boundary. Keep incomplete bytes here until complete.
    std::string         pending_utf8;
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
    const jsize length = env->GetStringLength(s);
    const jchar * chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length
            && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[++i] - 0xDC00);
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
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
    bool cancelled = false;
};

bool progressThunk(float progress, void * user_data) {
    auto * st = static_cast<ProgressState *>(user_data);
    if (st != nullptr && st->cb != nullptr && g_progress_on_progress != nullptr) {
        st->env->CallVoidMethod(st->cb, g_progress_on_progress, progress);
        if (st->env->ExceptionCheck()) { st->env->ExceptionClear(); st->cancelled = true; return false; }
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


size_t completeUtf8PrefixLength(const std::string & bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const auto lead = static_cast<unsigned char>(bytes[i]);
        size_t width = 0;
        if (lead <= 0x7F) width = 1;
        else if (lead >= 0xC2 && lead <= 0xDF) width = 2;
        else if (lead >= 0xE0 && lead <= 0xEF) width = 3;
        else if (lead >= 0xF0 && lead <= 0xF4) width = 4;
        else { ++i; continue; }

        if (bytes.size() - i < width) {
            bool onlyContinuationTail = true;
            for (size_t j = i + 1; j < bytes.size(); ++j) {
                if ((static_cast<unsigned char>(bytes[j]) & 0xC0) != 0x80) {
                    onlyContinuationTail = false;
                    break;
                }
            }
            if (onlyContinuationTail) return i;
            ++i;
            continue;
        }

        bool valid = true;
        for (size_t j = 1; j < width; ++j) {
            if ((static_cast<unsigned char>(bytes[i + j]) & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (valid && width == 3) {
            const auto second = static_cast<unsigned char>(bytes[i + 1]);
            valid = !((lead == 0xE0 && second < 0xA0) ||
                      (lead == 0xED && second >= 0xA0));
        } else if (valid && width == 4) {
            const auto second = static_cast<unsigned char>(bytes[i + 1]);
            valid = !((lead == 0xF0 && second < 0x90) ||
                      (lead == 0xF4 && second > 0x8F));
        }
        i += valid ? width : 1;
    }
    return i;
}

bool deliverUtf8(JNIEnv * env, jobject callback, const std::string & bytes) {
    if (bytes.empty()) return true;
    jstring piece = utf8ToJString(env, bytes);
    if (piece == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(callback, g_token_on_token, piece);
    const bool failed = env->ExceptionCheck();
    if (failed) env->ExceptionClear();
    env->DeleteLocalRef(piece);
    return !failed;
}

bool appendUtf8Piece(JNIEnv * env, jobject callback, std::string & pending,
                     const std::string & piece) {
    pending.append(piece);
    const size_t complete = completeUtf8PrefixLength(pending);
    if (complete == 0) return true;
    const std::string ready = pending.substr(0, complete);
    pending.erase(0, complete);
    return deliverUtf8(env, callback, ready);
}

bool flushUtf8Tail(JNIEnv * env, jobject callback, std::string & pending) {
    if (pending.empty()) return true;
    const size_t complete = completeUtf8PrefixLength(pending);
    std::string ready = pending.substr(0, complete);
    if (complete < pending.size()) ready.append("\xEF\xBF\xBD");
    pending.clear();
    return deliverUtf8(env, callback, ready);
}

// Auto uses conservative capability floors. Manual mode still allows a
// device-specific comparison on GPUs that fall below the heuristic.
bool suitableGpu() {
#ifdef __ANDROID__
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    VkInstance instance;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) return false;
    uint32_t count = 0;
    bool suitable = false;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
            VkPhysicalDeviceProperties p{};
            vkGetPhysicalDeviceProperties(devices.front(), &p);
            std::string name(p.deviceName);
            suitable = p.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU &&
                p.apiVersion >= VK_API_VERSION_1_1 && p.limits.maxComputeWorkGroupInvocations >= 128 &&
                p.limits.maxComputeSharedMemorySize >= 16384 &&
                name.find("Mali-G610") == std::string::npos && name.find("Mali-G52") == std::string::npos &&
                name.find("Mali-G57") == std::string::npos;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return suitable;
#else
    return false;
#endif
}

bool hasVulkanComputeDevice() {
    try {
        ggml_backend_reg_t vulkan = ggml_backend_reg_by_name("Vulkan");
        return vulkan != nullptr && ggml_backend_reg_dev_count(vulkan) > 0;
    } catch (const std::exception & e) {
        LOGE("Vulkan device probe failed: %s", e.what());
    } catch (...) {
        LOGE("Vulkan device probe failed with an unknown error");
    }
    return false;
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
    double top_p, min_p;
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
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(o.top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(o.min_p, 1));
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
// Batch positions are explicit because accepted target tokens can replace a proposed suffix.
bool decodeTokens(NativeEngine * e, const llama_token * tokens, int count, int pos, bool all_logits) {
    std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(
        llama_batch_ext_init(e->ctx), llama_batch_ext_free);
    if (!batch) return false;
    for (int i = 0; i < count; ++i) {
        int idx = llama_batch_ext_add_token(batch.get(), 0, tokens[i]);
        llama_pos at = pos + i;
        if (idx < 0 || !llama_batch_ext_set_pos(batch.get(), idx, &at) ||
            !llama_batch_ext_set_output_logits(batch.get(), idx, all_logits || i == count - 1)) return false;
    }
    return llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) == 0;
}

void clearHistory(NativeEngine * e) {
    llama_memory_clear(llama_get_memory(e->ctx), false);
    e->cached_tokens.clear();
    e->n_past = 0;
}

bool draftGuesses(NativeEngine * target, llama_token confirmed, int limit,
                  bool & synchronized, std::vector<llama_token> & batch) {
    auto * d = target->draft;
    int prefix = 0;
    if (synchronized) {
        // The previous block began with a confirmed target token. All positions
        // before the next mismatch have already been accepted by the target.
        prefix = std::min(d->n_past, target->n_past);
    } else {
        while (prefix < d->n_past && prefix < target->n_past &&
               d->cached_tokens[prefix] == target->cached_tokens[prefix]) ++prefix;
    }
    if (prefix < d->n_past && !llama_memory_seq_rm(llama_get_memory(d->ctx), 0, prefix, -1)) {
        clearHistory(d);
        prefix = 0;
    }
    d->cached_tokens.resize(prefix);
    d->n_past = prefix;
    while (prefix < target->n_past && !target->stop.load()) {
        int n = std::min(64, target->n_past - prefix);
        auto start = target->cached_tokens.data() + prefix;
        if (!decodeTokens(d, start, n, prefix, false)) return false;
        d->cached_tokens.insert(d->cached_tokens.end(), start, start + n);
        prefix += n;
        d->n_past = prefix;
    }
    synchronized = true;
    llama_token token = confirmed;
    for (int i = 0; i < limit && !target->stop.load(); ++i) {
        if (!decodeTokens(d, &token, 1, d->n_past, false)) return false;
        d->cached_tokens.push_back(token);
        ++d->n_past;
        auto logits = llama_get_logits_ith(d->ctx, -1);
        if (!logits) return false;
        token = static_cast<llama_token>(std::max_element(logits, logits + llama_vocab_n_tokens(d->vocab)) - logits);
        if (llama_vocab_is_eog(d->vocab, token)) break;
        batch.push_back(token);
    }
    return true;
}

int runSamplingLoop(JNIEnv * env, NativeEngine * e, jobject cb,
                    llama_sampler * chain, jint max_tokens) {
    const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
    const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

    const auto t0 = std::chrono::steady_clock::now();
    int n_gen = 0;
    int status = 0; // 0 = finished, 1 = stopped/aborted, 2 = context full
    bool reachedEog = false;
    std::vector<llama_token_data> candidates(static_cast<size_t>(n_vocab));

    std::vector<llama_token> verified;
    int next = 0;
    int logits_index = -1;
    bool draft_synced = false;
    while (!e->stop.load()) {
        if (n_gen >= max_tokens) break;
        if (e->n_past >= n_ctx - 1) {
            status = 2;
            callOnError(env, cb, "context window is full — start a new chat");
            break;
        }

        float * logits = llama_get_logits_ith(e->ctx, logits_index);
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

        if (llama_vocab_is_eog(e->vocab, tok)) {
            reachedEog = true;
            break;
        }

        if (n_gen == 0) e->ttft_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - e->started).count();
        const std::string piece = tokenToPiece(e->vocab, tok);
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        e->decode_rate = seconds > 0 ? (n_gen + 1) / seconds : 0;
        if (!piece.empty() && !appendUtf8Piece(env, cb, e->pending_utf8, piece)) {
            status = -1;
            break;
        }
        n_gen++;

        if (next < static_cast<int>(verified.size()) && verified[next] == tok) {
            logits_index = next++;
        } else {
            if (next < static_cast<int>(verified.size()) &&
                !llama_memory_seq_rm(llama_get_memory(e->ctx), 0, e->n_past, -1)) {
                clearHistory(e);
                status = -1;
                break;
            }
            verified.assign(1, tok);
            const int limit = std::min({4, n_ctx - e->n_past - 2, max_tokens - n_gen});
            if (e->draft && limit > 0 && !draftGuesses(e, tok, limit, draft_synced, verified)) {
                clearHistory(e->draft);
                e->draft = nullptr;
                verified.resize(1);
            }
            if (!decodeTokens(e, verified.data(), static_cast<int>(verified.size()), e->n_past, true)) {
                clearHistory(e);
                status = -1;
                break;
            }
            next = 1;
            logits_index = 0;
        }
        e->cached_tokens.push_back(tok);
        e->n_past++;
    }

    // ContinueGenerate expects the last logits row to describe the committed tail.
    if (status >= 0 && next < static_cast<int>(verified.size()) && e->n_past > 0) {
        int last = e->n_past - 1;
        if (!llama_memory_seq_rm(llama_get_memory(e->ctx), 0, last, -1) ||
            !decodeTokens(e, &e->cached_tokens[last], 1, last, false)) {
            clearHistory(e);
            status = -1;
        }
    }
    if (reachedEog && !flushUtf8Tail(env, cb, e->pending_utf8)) status = -1;

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
Java_com_littlebit_chat_engine_LlamaJni_nativeInit(JNIEnv * env, jobject) {
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
            g_prefill = getMethod(env, g_token_cb_class, "onPrefill", "(II)V");
            g_token_on_error       = getMethod(env, g_token_cb_class, "onError", "(Ljava/lang/String;)V");
        } catch (const std::exception & e) {
            LOGE("callback init failed: %s", e.what());
        }
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint n_batch,
        jint n_gpu_layers, jboolean flash, jobject jprogress) {
    try {
        const std::string path = toStdString(env, jpath);
        auto engine = std::make_unique<NativeEngine>();

        jobject progress_ref = nullptr;
        std::unique_ptr<ProgressState> pst;
        if (jprogress != nullptr) {
            progress_ref = env->NewGlobalRef(jprogress);
            if (progress_ref == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                throw std::runtime_error("could not retain model-load progress callback");
            }
            pst = std::make_unique<ProgressState>(ProgressState{env, progress_ref});
        }

        const int requested_gpu_layers = n_gpu_layers == -1 ? (suitableGpu() ? 99 : 0) : std::max(0, static_cast<int>(n_gpu_layers));
        const bool vulkan_ready = requested_gpu_layers > 0 && hasVulkanComputeDevice();
        if (requested_gpu_layers > 0 && !vulkan_ready) {
            LOGE("Vulkan offload requested without a usable Vulkan compute device; falling back to CPU/KleidiAI");
        }

        const int attempts[] = { vulkan_ready ? requested_gpu_layers : 0, 0 };
        const size_t attempt_count = vulkan_ready ? 2 : 1;
        std::string last_error = "unknown model load failure";
        bool loaded = false;

        for (size_t attempt = 0; attempt < attempt_count; ++attempt) {
            const int layers = attempts[attempt];
            try {
                llama_model_params mparams = llama_model_default_params();
                // Zero GPU layers alone still lets AUTO inspect GPU devices
                // and disable mmap when a device lacks mmap support.
                static ggml_backend_dev_t cpu_only[] = {nullptr};
                if (layers == 0) {
                    mparams.devices = cpu_only;
                    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
                }
                mparams.n_gpu_layers = layers;
                mparams.progress_callback = pst ? progressThunk : nullptr;
                mparams.progress_callback_user_data = pst.get();

                if (path.rfind("/proc/self/fd/", 0) == 0) {
                    // SAF may grant reads on the open descriptor but deny reopening the path.
                    const int fd = dup(std::stoi(path.substr(14)));
                    FILE * raw = fd >= 0 ? fdopen(fd, "rb") : nullptr;
                    if (fd >= 0 && !raw) close(fd);
                    std::unique_ptr<FILE, decltype(&fclose)> input(raw, fclose);
                    if (input && fseeko(input.get(), 0, SEEK_SET) == 0) {
                        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
                        engine->model = llama_model_load_from_file_ptr(input.get(), mparams);
                    }
                    if (!engine->model && !(pst && pst->cancelled)) {
                        engine->model = llama_model_load_from_file(path.c_str(), mparams);
                    }
                } else {
                    engine->model = llama_model_load_from_file(path.c_str(), mparams);
                }
                if (engine->model == nullptr) {
                    throw std::runtime_error("llama_model_load_from_file returned null");
                }
                engine->vocab = llama_model_get_vocab(engine->model);

                llama_context_params cparams = llama_context_default_params();
                cparams.n_ctx           = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 2048;
                cparams.n_batch         = n_batch > 0 ? static_cast<uint32_t>(n_batch) : 512;
                cparams.n_ubatch        = std::min(cparams.n_batch, layers > 0 ? 128u : 64u);
                cparams.op_offload = layers > 0;
                cparams.offload_kqv = layers > 0;
                if (layers == 0) {
                    cparams.abort_callback = [](void * data) {
                        auto * e = static_cast<NativeEngine *>(data);
                        return e->in_prefill.load() && e->stop.load();
                    };
                    cparams.abort_callback_data = engine.get();
                }
                cparams.n_threads       = n_threads;
                cparams.n_threads_batch = n_threads;

                if (layers > 0 && flash) {
                    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
                    cparams.type_k = cparams.type_v = GGML_TYPE_Q8_0;
                    engine->ctx = llama_init_from_model(engine->model, cparams);
                }
                if (!engine->ctx) {
                    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
                    cparams.type_k = cparams.type_v = GGML_TYPE_F16;
                    engine->ctx = llama_init_from_model(engine->model, cparams);
                }
                if (engine->ctx == nullptr) {
                    throw std::runtime_error("failed to create llama context");
                }
                engine->params = cparams;
                engine->gpu_layers = layers;
                loaded = true;
                break;
            } catch (const std::exception & e) {
                last_error = e.what();
            } catch (...) {
                last_error = "unknown exception while initializing model/backend";
            }

            if (engine->ctx != nullptr) {
                llama_free(engine->ctx);
                engine->ctx = nullptr;
            }
            if (engine->model != nullptr) {
                llama_model_free(engine->model);
                engine->model = nullptr;
            }
            engine->vocab = nullptr;
            if (pst && pst->cancelled) break;
            if (layers > 0) {
                LOGE("Vulkan model/context initialization failed (%s); retrying CPU/KleidiAI",
                     last_error.c_str());
            }
        }

        if (progress_ref != nullptr) env->DeleteGlobalRef(progress_ref);
        pst.reset();

        if (!loaded) {
            throw std::runtime_error("failed to load model " + path + ": " + last_error);
        }
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & e) {
        throwIllegalState(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelDesc(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return nullptr;
    char buf[256];
    llama_model_desc(e->model, buf, sizeof(buf));
    return utf8ToJString(env, std::string(buf));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelGpuLayers(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    return e != nullptr ? e->gpu_layers : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelSizeBytes(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return 0;
    return static_cast<jlong>(llama_model_size(e->model));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelFtype(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return nullptr;
    return env->NewStringUTF(llama_ftype_name(llama_model_ftype(e->model)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeFormatChat(
        JNIEnv * env, jobject, jlong handle, jstring jsystem, jobjectArray jroles, jobjectArray jcontents) {
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
                return utf8ToJString(env, std::string(buf.data(), static_cast<size_t>(len)));
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
        return utf8ToJString(env, out);
    } catch (const std::exception & e) {
        throwIllegalState(env, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring jprompt,
        jdouble temp, jint top_k, jdouble top_p, jdouble min_p, jdouble repeat_penalty, jdouble dry_multiplier,
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
        e->started = std::chrono::steady_clock::now();
        e->ttft_ms = 0;
        e->decode_rate = 0;
        e->stop.store(false);
        // A fresh prompt owns a fresh byte stream. Only a same-turn
        // nativeContinueGenerate may finish a code point from the prior call.
        e->pending_utf8.clear();
        const std::string prompt = toStdString(env, jprompt);
        const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
        const int logical_batch = static_cast<int>(llama_n_batch(e->ctx));
        const int n_batch = std::min(e->gpu_layers > 0 ? 128 : 64, logical_batch);
        const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

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
        opts.top_p = top_p;
        opts.min_p = min_p;
        opts.temp           = temp;
        opts.top_k          = top_k;
        opts.repeat_penalty = repeat_penalty;
        opts.dry_multiplier = dry_multiplier;
        opts.seed           = static_cast<long>(seed);
        llama_sampler * chain = buildSampler(e->vocab, n_vocab, opts);
        if (chain == nullptr) { env->DeleteGlobalRef(cb); throwIllegalState(env, "could not create sampler"); return -1; }

        bool failed = false;

        // Re-evaluate the final prompt token even for an exact match: the
        // sampling loop needs logits for that position.
        int lcp = 0;
        while (lcp < n_prompt - 1 && lcp < e->n_past &&
               e->cached_tokens[lcp] == prompt_tokens[lcp]) ++lcp;
        auto mem = llama_get_memory(e->ctx);
        if ((lcp == 0 && e->n_past > 0) || (lcp < e->n_past && (llama_memory_seq_pos_min(mem, 0) > 0 ||
                !llama_memory_seq_rm(mem, 0, lcp, -1)))) {
            llama_memory_clear(mem, false);
            lcp = 0;
        }
        e->cached_tokens.resize(lcp);
        e->n_past = lcp;
        const auto prefill_start = std::chrono::steady_clock::now();
        e->in_prefill.store(true);
        env->CallVoidMethod(cb, g_prefill, lcp, n_prompt);
        for (int i0 = lcp; i0 < n_prompt && !e->stop.load(); ) {
            const int step = e->conserve.load() ? 16 : (n_prompt - lcp <= 128 ? 16 : (i0 == lcp ? 16 : n_batch));
            const int chunk = std::min({step, n_batch, n_prompt - i0});
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
            e->cached_tokens.insert(e->cached_tokens.end(), prompt_tokens.begin() + i0,
                                    prompt_tokens.begin() + i0 + chunk);
            e->n_past += chunk;
            i0 += chunk;
            env->CallVoidMethod(cb, g_prefill, e->n_past, n_prompt);
        }

        e->in_prefill.store(false);
        const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - prefill_start).count();
        e->prefill_rate = secs > 0 ? (e->n_past - lcp) / secs : 0;
        if (failed) {
            llama_memory_clear(mem, false);
            e->cached_tokens.clear();
            e->n_past = 0;
        }
        int status = 0;
        if (!failed && !e->stop.load()) {
            status = runSamplingLoop(env, e, cb, chain, max_tokens);
        }
        if (chain != nullptr) { llama_sampler_free(chain); chain = nullptr; }

        env->DeleteGlobalRef(cb);

        if (e->stop.load()) return 1;
        if (failed) return -1;
        return status;
    } catch (const std::exception & ex) {
        callOnError(env, cb, ex.what());
        env->DeleteGlobalRef(cb);
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeContinueGenerate(
        JNIEnv * env, jobject, jlong handle,
        jdouble temp, jint top_k, jdouble top_p, jdouble min_p, jdouble repeat_penalty, jdouble dry_multiplier,
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
    if (cb == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        throwIllegalState(env, "could not retain generation callback");
        return -1;
    }

    // Continue sampling from the current KV state (speculative pre-warm flush):
    // no prompt re-processing, logits of the last decoded token are reused.
    try {
        e->started = std::chrono::steady_clock::now();
        e->ttft_ms = 0;
        e->decode_rate = 0;
        e->stop.store(false);
        const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

        SamplerOpts opts;
        opts.top_p = top_p;
        opts.min_p = min_p;
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
Java_com_littlebit_chat_engine_LlamaJni_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e != nullptr) e->stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeConserve(JNIEnv *, jobject, jlong h, jboolean on) {
    if (auto * e = reinterpret_cast<NativeEngine *>(h)) e->conserve.store(on);
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeSynchronize(JNIEnv *, jobject, jlong h) {
    if (auto * e = reinterpret_cast<NativeEngine *>(h)) llama_synchronize(e->ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeSetThreads(JNIEnv *env, jobject, jlong handle, jint threads, jint batch, jintArray cores) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e != nullptr && e->ctx != nullptr && threads > 0) {
        const int size = std::max(threads, batch);
        std::vector<jint> ids(env->GetArrayLength(cores));
        if (!ids.empty()) env->GetIntArrayRegion(cores, 0, ids.size(), ids.data());
        if (e->pool_size != size || e->pool_cores != ids) {
            if (e->pool) {
                llama_detach_threadpool(e->ctx);
                ggml_threadpool_free(e->pool);
                e->pool = nullptr;
            }
            e->pool_size = size;
            e->pool_cores = ids;
            if (!ids.empty()) {
                auto p = ggml_threadpool_params_default(size);
                p.poll = 0;
                for (auto id : ids) if (id >= 0 && id < GGML_MAX_N_THREADS) p.cpumask[id] = true;
                e->pool = ggml_threadpool_new(&p);
                if (e->pool) llama_attach_threadpool(e->ctx, e->pool, nullptr);
            }
        }
        llama_set_n_threads(e->ctx, threads, batch);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeUnload(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr) return;
    if (e->pool) { llama_detach_threadpool(e->ctx); ggml_threadpool_free(e->pool); }
    if (e->ctx != nullptr) llama_free(e->ctx);
    if (e->owns_model && e->model != nullptr) llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeReset(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    llama_memory_clear(llama_get_memory(e->ctx), false);
    e->cached_tokens.clear();
    e->pending_utf8.clear();
    e->n_past = 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativePromptSize(JNIEnv * env, jobject, jlong handle, jstring prompt) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    const auto text = toStdString(env, prompt);
    return -llama_tokenize(e->vocab, text.data(), text.size(), nullptr, 0, false, true);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeMetrics(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    const double values[] = {e->ttft_ms, e->prefill_rate, static_cast<double>(e->n_past),
                             static_cast<double>(llama_n_ctx(e->ctx)), e->decode_rate};
    auto result = env->NewDoubleArray(5);
    if (result) env->SetDoubleArrayRegion(result, 0, 5, values);
    return result;
}

// The caller holds the parent engine mutex until this context is destroyed.
extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeBenchmarkContext(JNIEnv *env, jobject, jlong handle) {
    auto * parent = reinterpret_cast<NativeEngine *>(handle);
    try {
        auto isolated = std::make_unique<NativeEngine>();
        isolated->model = parent->model;
        isolated->vocab = parent->vocab;
        isolated->owns_model = false;
        isolated->gpu_layers = parent->gpu_layers;
        auto params = parent->params;
        params.n_ctx = std::min(params.n_ctx, 512u);
        if (params.abort_callback) params.abort_callback_data = isolated.get();
        isolated->ctx = llama_init_from_model(parent->model, params);
        if (!isolated->ctx) throw std::runtime_error("Insufficient memory for benchmark context");
        return reinterpret_cast<jlong>(isolated.release());
    } catch (const std::exception &error) {
        throwIllegalState(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeAttachDraft(JNIEnv *, jobject, jlong handle, jlong draft) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    auto * d = reinterpret_cast<NativeEngine *>(draft);
    e->draft = nullptr;
    if (!d) return true;
    for (auto * model : {e->model, d->model}) {
        if (llama_model_is_recurrent(model) || llama_model_is_hybrid(model) || llama_model_n_swa(model)) return false;
    }
    if (llama_n_ctx(d->ctx) < llama_n_ctx(e->ctx) ||
        llama_vocab_type(e->vocab) != llama_vocab_type(d->vocab) ||
        llama_vocab_n_tokens(e->vocab) != llama_vocab_n_tokens(d->vocab) ||
        llama_vocab_bos(e->vocab) != llama_vocab_bos(d->vocab) ||
        llama_vocab_eos(e->vocab) != llama_vocab_eos(d->vocab)) return false;
    for (int i = 0; i < llama_vocab_n_tokens(e->vocab); ++i) {
        if (tokenToPiece(e->vocab, i) != tokenToPiece(d->vocab, i) ||
            llama_vocab_get_attr(e->vocab, i) != llama_vocab_get_attr(d->vocab, i) ||
            llama_vocab_is_eog(e->vocab, i) != llama_vocab_is_eog(d->vocab, i)) return false;
    }
    e->draft = d;
    return true;
}
