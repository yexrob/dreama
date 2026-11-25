# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# AirPlay receiver: libairplay finds its callbacks by name
-keep class com.limelight.local.airplay.AirPlayNative {*;}

# LiteRT GPU delegate, loaded by name from native code. The runtime AAR
# ships its own rules, the GPU one does not.
-keep class org.tensorflow.lite.gpu.** {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# QuickJS-KT JNI callbacks
-keep class com.dokar.quickjs.** { *; }

# Jsoup container compaction fields used by PluginDomParser
-keepclassmembers class org.jsoup.nodes.Attributes {
    int size;
    java.lang.String[] keys;
}
-keepclassmembers class org.jsoup.nodes.Element {
    org.jsoup.nodes.Attributes attributes;
    org.jsoup.nodes.Element$NodeList childNodes;
    private static final org.jsoup.nodes.Element$NodeList EmptyNodeList;
}
-keepclassmembers class org.jsoup.nodes.LeafNode {
    java.lang.Object value;
}
-keepclassmembers class org.jsoup.nodes.FormElement {
    private final java.util.ArrayList linkedEls;
}
