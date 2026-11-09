# Nova — Türkçe sesli asistan

Nova 0.2, Android 8.0+ için yazılı ve sesli komut uygulamasıdır. Ana ekrandaki mikrofonla uygulamanın içinde konuşabilir veya alttaki mesaj kutusuna yazabilirsin. Yanıtlar sohbet kartlarında gösterilir; cihazda Türkçe ses motoru hazırsa seslendirilir.

## Neler yapabilir?

- Yazılı sohbet ve cihazda saklanan son 100 mesaj; sohbeti temizleme.
- Uygulama içi dinleme, kısmi konuşma metni, görünür dinleme/yanıt/seslendirme durumları ve durdurma.
- Saat/tarih ve kısa Türkçe diyaloglar.
- Hesaplama: **hesapla 12 çarpı 7**, **hesapla (2 + 3) * 4**, virgüllü sayılar.
- Notlar: **not al**, ardından notun; **not al yarın kitap al**, **notlarımı göster**. Not silme ayrıca onay ister.
- **internette Türk kahvesi ara**, **haritada eczane ara**.
- **YouTube aç**, **WhatsApp aç**, **Spotify aç**, **tarayıcıyı aç**. Uygulama yüklü değilse mağazaya gitmeden önce sorar.
- **Wi-Fi ayarlarını aç**, **Bluetooth ayarlarını aç**, **telefon ayarlarını aç**.
- Ayarlardan asistan adı, sesli yanıt aç/kapat, hız, ses tonu ve kurulu Türkçe ses seçimi.
- **Sesi dene**, ses paketi kurulumu, konuşma hizmeti, medya sesi ve mikrofon izni ayarlarına erişim.

Notlar cihazda saklanır; sohbeti temizlemek notları silmez. **Yardım** komutu örnekleri gösterir.

## Ses çalışmıyorsa

Ayarları sağ üstteki düğmeden veya ekrandaki **Ses ayarlarını aç** uyarısından aç.

| Durum | Uygulamadaki çözüm |
| --- | --- |
| Ses motoru yok | **Türkçe ses paketi / ses motoru** ile uyumlu bir motor kur veya seç. |
| Türkçe ses paketi eksik | Telefonun metin okuma ayarlarından Türkçe verisini indir; Nova'ya dönüp **Sesi dene**. |
| Ses çıkmıyor | **Medya sesini ayarla**; telefonun ses düğmeleri de Nova açıkken medya sesini kontrol eder. |
| Mikrofon izni reddedildi | **Mikrofon izni ayarları**; yazılı sohbet kullanılmaya devam eder. |
| Tanıma hizmeti yok | **Konuşma tanıma hizmeti** ile hizmet kur/seç. |
| Tanıma internet hatası veriyor | Cihazda Türkçe cihaz içi model varsa Nova onu tercih eder; diğer hizmetler internet gerektirebilir. |

Ses isteği motor hazır olmadan gelirse son yanıt hazır olana kadar tutulur. **Konuşuyor** durumu, motorun gerçek başlama olayından sonra gösterilir. Motor/veri eksikliği, sessiz medya ve bağlantı hataları gizlenmez. Nova kendi sunucusuna ses göndermez; seçili Android tanıma hizmetinin işleme koşulları geçerlidir.

## APK indirme ve kurma

1. [Actions → Nova Android APK](https://github.com/riza74721-blip/Nova/actions/workflows/setup.yml) sayfasındaki son başarılı çalıştırmayı aç.
2. **Artifacts → Nova-APK** dosyasını indir; ZIP içindeki **app-debug.apk** dosyasını yükle.
3. Ayarlardan **Sesi dene**, ardından mikrofon ve yazılı mesaj akışını dene.

APK debug imzalıdır. Önceki bir debug APK farklı anahtarla imzalandıysa Android üzerine kurulumu reddedebilir; bu durumda önceki Nova'yı kaldırıp yenisini kurmak gerekir. Kaldırma uygulamanın yerel verilerini de siler. Mağaza için sabit release imzası hazırlanmadı.

Artifact içindeki checksum, APK ile aynı dizinde doğrulanabilir:

~~~sh
sha256sum -c app-debug.apk.sha256
~~~

APK artifact'i 30 gün, test raporları 14 gün saklanır. Yeni bir derleme için **Run workflow → main → Run workflow** kullanılır.

## Derleme ve test

JDK 17, Android SDK Platform 35 ve Build Tools 35.0.0 gerekir:

~~~sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses
export ANDROID_HOME=/SDK/kurulum/dizini
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
~~~

Windows'ta gradlew.bat kullanılır. SDK yolu local.properties dosyasına sdk.dir=... olarak da yazılabilir. APK: app/build/outputs/apk/debug/app-debug.apk.

Gradle Wrapper 8.9 ve dağıtım SHA-256 doğrulaması; AGP 8.7.3, Kotlin 2.0.21, compile/target SDK 35, min SDK 26 kullanılır. İlk derleme bağımlılıkları indirmek için internet ister.

Temiz bir Android 15 / API 35 emülatöründe:

~~~sh
./gradlew --no-daemon connectedDebugAndroidTest
~~~

Birim testleri hesaplamayı, Türkçe komutları, not diyaloglarını ve güvenli eylem yönlendirmesini kapsar. Cihaz testleri yazılı yanıtları, kalıcı geçmiş/notları, ses ayarlarını, gerçek mikrofon izni reddini ve konuşma sonucu → gerçek komut motoru → seslendirme isteği zincirini denetler. Ses testlerinde yalnızca Android motor sınırı test motoruyla değiştirilir; testin geçmesi gerçek mikrofondaki Türkçe doğruluğunu veya fiziksel cihazın ses kalitesini kanıtlamaz. Yerel ses hizmetleriyle ana ekran, sohbet ve ayarlar ekran görüntüleri de alınır.

CI APK imzasını doğrular ve cihaz testleri geçtikten sonra Nova-APK yükler. XML/HTML raporları, günlükler ve ekran görüntüleri **Nova-test-raporlari** artifact'indedir.

## Kaynak arşivi

NovaAndroid-source.zip özgün haliyle korunur. Güncel kaynaklar app/ ve kökteki Gradle dosyalarıdır. [Arşiv inceleme notları](docs/SOURCE_REVIEW.md).

## Kapsam

Bu sürüm yerel komutlar ve kısa diyaloglar kullanır; genel sorulara yapay zekâ üretimi cevap veren bir API bağlantısı içermez. Kilit ekranında sesle uyandırma, sürekli arka plan mikrofonu, otomatik arama/mesaj gönderme veya kablosuz bağlantıları kendiliğinden değiştirme yapmaz. Android ses motoru/Türkçe veri ve konuşma tanıma hizmeti bulunmayan cihazda önce ilgili hizmetin kurulması gerekir; yazılı özellikler bu hizmetlerden bağımsızdır.
