# Keep Hush daemon entry point & contract classes intact for app_process64
-keep class com.aileboeth.hush.daemon.DaemonMain {
    public static void main(java.lang.String[]);
}
-keep class com.aileboeth.hush.daemon.** { *; }
-keep class com.aileboeth.hush.contract.** { *; }
-keep class com.aileboeth.hush.bootstrap.PairingNotificationReceiver { *; }

# Keep BouncyCastle provider & X.509 signing internals used in wireless ADB TLS pairing
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
