# Nova Android Sesli Asistan

Android 8.0+ (API 26) için deneysel Türkçe sesli asistan. Mikrofon düğmesine basın, izin verin ve **merhaba**, **saat kaç**, **tarih**, **bugün günlerden ne** veya **adın ne** deyin. Yanıt ekranda gösterilir; telefonda Türkçe metin okuma sesi varsa seslendirilir. Kaydırıcı konuşma hızını ayarlar.

## APK indirme

1. [Actions → Nova Android APK](https://github.com/riza74721-blip/Nova/actions/workflows/setup.yml) sayfasını açın.
2. Son başarılı çalıştırmayı seçin; **Artifacts → Nova-APK** dosyasını indirin.
3. ZIP içindeki `app-debug.apk` dosyasını telefona yükleyin. APK debug anahtarıyla imzalanır; mağaza dağıtımı için hazırlanmış release sürümü değildir.

Artifact içindeki `app-debug.apk.sha256` dosyasıyla indirmeyi kontrol edebilirsiniz: `sha256sum -c app-debug.apk.sha256`. Artifact'ler 30 gün saklanır. Yeni bir derleme için **Run workflow → main → Run workflow** kullanılabilir.

## Yerel derleme ve test

JDK 17 ve Android SDK gerekir. Android Studio ile bu dizini açabilir veya komut satırından çalıştırabilirsiniz:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses
export ANDROID_HOME=/SDK/kurulum/dizini
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Windows'ta `gradlew.bat` kullanın. SDK yolunu `local.properties` içine `sdk.dir=...` olarak da yazabilirsiniz. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Gradle Wrapper **8.9** kullanır ve dağıtımın SHA-256 değerini doğrular. Android Gradle Plugin **8.7.3**, Kotlin **2.0.21**, compile/target SDK **35**, Build Tools **35.0.0** sürümlerine sabitlenmiştir. İlk derlemede bağımlılıkları indirmek için internet gerekir.

Temiz bir Android 15 / API 35 AOSP emülatöründe cihaz testleri:

```sh
./gradlew --no-daemon connectedDebugAndroidTest
```

CI; birim testleri, Android lint, APK/test APK derlemesi, APK imzası kontrolü ve emülatör testlerini çalıştırır. Emülatör testleri izinsiz açılışı, mikrofon iznini reddetmeyi ve tanıma hizmeti olmayan cihazdaki hata mesajını denetler. Komut testleri Türkçe büyük harfleri, boş girdiyi, saat/tarih ve bilinmeyen komutları kapsar. Raporlar **Nova-test-raporlari** artifact'inde saklanır. `Nova-APK` yalnızca bütün adımlar geçtiğinde yüklenir.

## Kaynak ZIP ve SDK hatası

`NovaAndroid-source.zip` özgün arşiv olarak korunur. Güncel ve derlenen kaynaklar deponun kökündeki `app/` ve Gradle dosyalarıdır; CI ZIP'i tekrar açıp düzeltmeleri ezmez. Arşiv inceleme notları: [docs/SOURCE_REVIEW.md](docs/SOURCE_REVIEW.md).

Önceki SDK adımı artık SDK deposunda bulunmayan `tools` paketini istemekteydi. Workflow yalnızca gerekli paketleri açıkça kurar; ayrıca SDK adımındaki bozuk YAML girintisi giderilmiştir.

## Kullanım sınırları

Bu sürüm sürekli arka plan dinlemesi, kilit ekranında “Nova” uyandırma, kişiye özel ses tanıma, telefon kontrolü veya yapay zekâ API bağlantısı içermez. Android'in konuşma tanıma ekranını açar; tanıma için telefonda uyumlu bir hizmet ve bazı cihazlarda internet gerekir. Türkçe TTS sesi bulunmazsa uygulama bunu bildirir ve yanıtı ekranda gösterir. Emülatör testleri gerçek Türkçe ses tanıma ve ses kalitesini doğrulamaz; bu özellikler uygun hizmetlerin kurulu olduğu fiziksel cihazda ayrıca denenmelidir.
