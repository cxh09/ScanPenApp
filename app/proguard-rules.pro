# =============================================================================
# ScanPenApp ProGuard / R8 keep rules
# -----------------------------------------------------------------------------
# 设备：词典笔（低端 SoC、≤2GB RAM）。R8 开启后必须按本文件 keep 关键反射点，
# 否则以下依赖会在 release 运行时崩溃：OpenAI client（kotlinx-serialization）、
# Ktor、ML Kit barcode、Markwon、CameraX。
#
# 原则：
#   1. 只对真正用到反射 / 动态代理 / JNI 的部分做 keep，避免「一 keep 就全留」。
#   2. 业务层用 @Keep 标注（见下文）兜底；不要把整个 com.cxh09.scanpenapp.** 保留。
#   3. 规则按依赖分组，每组先 -keep / -keepclassmembers，最后 -dontwarn 兜底警告。
# =============================================================================

# -----------------------------------------------------------------------------
# 通用属性
# -----------------------------------------------------------------------------
# 保留行号/源文件名：release 崩溃栈可定位；文件名重命名以避免源码结构外泄
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 反射与泛型擦除相关：kotlinx-serialization、Retrofit、Ktor、Annotation 处理器都依赖
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault

# -----------------------------------------------------------------------------
# Kotlin / 协程
# -----------------------------------------------------------------------------
# Kotlin 反射元数据（kotlin-reflect 不在依赖中，但 coroutines 内部仍用）
-dontwarn kotlin.reflect.jvm.internal.**

# kotlinx.coroutines：内部 ServiceLoader 加载 MainDispatcherFactory / CoroutineExceptionHandler
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.flow.** { *; }
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlinx.atomicfu.**

# Kotlin companion / object 入口（被序列化框架/反射调用）
-keepclassmembers class **$Companion {
    public <fields>;
}
-keepclassmembers class **$WhenMappings { *; }

# -----------------------------------------------------------------------------
# ViewBinding（编译期生成；显式声明以防 R8 把 inflate/bind 当未引用剪掉）
# -----------------------------------------------------------------------------
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static *** bind(android.view.View);
}

# Activity / Fragment / Service 等组件由 AAPT 自动 keep（Manifest 引用），无需重复。

# -----------------------------------------------------------------------------
# 业务层兜底：用 @Keep 标注需要保留名字/字段的类/方法
# -----------------------------------------------------------------------------
# 业务代码里若有反射/动态调用，请优先在源文件上加 androidx.annotation.Keep
# 注解；本文件不批量保留 com.cxh09.scanpenapp.**。

# -----------------------------------------------------------------------------
# kotlinx-serialization（OpenAI client 内部使用）
# -----------------------------------------------------------------------------
# 保留 @Serializable 类生成的 $serializer 伴生对象与序列化字段
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
# SerializationJson 解析器与模块
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# -----------------------------------------------------------------------------
# aallam/openai-client（基于 Ktor + kotlinx-serialization）
# -----------------------------------------------------------------------------
# 该库的 API/请求/响应模型全部用 @Serializable 注解；Ktor 的 ContentNegotiation
# 通过 serviceLoader 查找转换器，KSerializer 必须可被 Class.forName 找到
-keep class com.aallam.openai.api.** { *; }
-keep class com.aallam.openai.client.** { *; }
-keep class com.aallam.openai.api.chat.** { *; }
-keep class com.aallam.openai.api.completion.** { *; }
-keep class com.aallam.openai.api.embedding.** { *; }
-keep class com.aallam.openai.api.image.** { *; }
-keep class com.aallam.openai.api.models.** { *; }
-keep class com.aallam.openai.api.file.** { *; }
-keep class com.aallam.openai.api.finetune.** { *; }
-keep class com.aallam.openai.api.audio.** { *; }
-keep class com.aallam.openai.api.run.** { *; }
-keep class com.aallam.openai.api.assistant.** { *; }
-keep class com.aallam.openai.api.thread.** { *; }
-keep class com.aallam.openai.api.beta.** { *; }
-keep class com.aallam.openai.api.exception.** { *; }
-keep class com.aallam.openai.api.http.** { *; }
-keep class com.aallam.openai.api.logging.** { *; }
-dontwarn com.aallam.openai.**

# -----------------------------------------------------------------------------
# Ktor client（openai-client 传递依赖）
# -----------------------------------------------------------------------------
# Ktor 使用 ServiceLoader 加载 engines/plugins；引擎类名不能混淆
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.client.engine.** { *; }
-keepclassmembers class io.ktor.client.plugins.** { *; }
-keepclassmembers class io.ktor.client.** { *; }
-keep class io.ktor.client.plugins.contentnegotiation.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# -----------------------------------------------------------------------------
# OkHttp / Okio（Ktor OkHttp engine 传递依赖）
# -----------------------------------------------------------------------------
# OkHttp 平台检测在 release 下找不到 Conscrypt/BouncyCastle/OpenJSSE，
# 必须 -dontwarn 避免构建失败
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.publicsuffix.**
-dontwarn okio.**

# -----------------------------------------------------------------------------
# kotlinx-coroutines-core（Ktor/openai-client 传递依赖）
# 已在上方 kotlinx.coroutines 段处理；此处避免重复

# -----------------------------------------------------------------------------
# Markwon（CommonMark 解析 + 扩展）
# -----------------------------------------------------------------------------
# CommonMark 通过 ServiceLoader 加载节点渲染器；Markwon 内部 Spannable 构建
# 使用反射访问部分属性
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }
-keep class org.commonmark.ext.** { *; }
-keep class io.noties.markwon.ext.strikethrough.** { *; }
-keep class io.noties.markwon.ext.tables.** { *; }
-keepclassmembers class org.commonmark.node.** { *; }
-dontwarn io.noties.markwon.**
-dontwarn org.commonmark.**

# -----------------------------------------------------------------------------
# CameraX
# -----------------------------------------------------------------------------
# CameraX 通过 androidx.lifecycle 反射调用 Provider/Factory；保留核心实现类
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.core.impl.** { *; }
-keepclassmembers class androidx.camera.camera2.** { *; }
-keepclassmembers class androidx.camera.lifecycle.** { *; }
-keepclassmembers class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**

# -----------------------------------------------------------------------------
# ML Kit barcode（Play Services 依赖，含本地 native）
# -----------------------------------------------------------------------------
# ML Kit 通过 Play Services 加载模型，部分类经由反射创建
-keep class com.google.mlkit.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# -----------------------------------------------------------------------------
# ZXing（二维码解码，已与 ML Kit 二选一；保留以便后续切换）
# -----------------------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-keepclassmembers class com.google.zxing.** {
    public *** decode(...);
    public *** encode(...);
}
-dontwarn com.google.zxing.**

# -----------------------------------------------------------------------------
# AndroidX core-ktx / appcompat / material
# -----------------------------------------------------------------------------
# 这些库由 Android Gradle Plugin 自动 keep 关键类，补充一下常见的反射点
-dontwarn androidx.core.**
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.**

# -----------------------------------------------------------------------------
# 第三方小依赖兜底
# -----------------------------------------------------------------------------
# kotlinx-datetime（openai-client 间接依赖，处理 Instant）
-dontwarn kotlinx.datetime.**
# JNA / 平台绑定（Ktor 在 Windows/macOS 上的 native engine，本项目用不到）
-dontwarn com.sun.jna.**
# Conscrypt / BouncyCastle 的可选依赖
-dontwarn javax.annotation.**

# -----------------------------------------------------------------------------
# 调试期可选：输出 mapping / seeds / usage，便于排查被误剪的类
# 上线前建议删除 printseeds/printusage（mapping 必须保留以解码线上崩溃栈）
# -----------------------------------------------------------------------------
#-printmapping mapping.txt
#-printseeds seeds.txt
#-printusage usage.txt
