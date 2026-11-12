# WoL Denetim Merkezi — R8 kuralları (S13).
# Hilt, Room, WorkManager, DataStore ve Compose kendi tüketici kurallarını getirir.

# Çökme kayıtları okunabilir kalsın (satır numaraları).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager işçileri ve yayın alıcıları ad ile (manifest / yansıma) yaratılır.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Room'un şema/enum dönüştürücüleri enum adlarını kullanır (RunResult, PowerAction…): valueOf/values kalsın.
-keepclassmembers enum com.kdgames.wake_on_lan.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
