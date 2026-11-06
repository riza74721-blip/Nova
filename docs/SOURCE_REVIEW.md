# Özgün kaynak arşivi incelemesi

İncelenen arşiv: `NovaAndroid-source.zip`. Özgün dosya değiştirilmeden saklanır.

Arşiv; `NovaAndroid/` altında Gradle yapılandırması, iki Kotlin sınıfı, Android manifesti, README ve ayrı bir workflow içerir. Gradle Wrapper içermez; kökteki workflow ZIP'i açarak derlemeye çalışıyordu. Güncel yapı kaynakları doğrudan Git'ten derler ve doğrulanabilir Gradle Wrapper kullanır.

- Manifest yalnızca `MainActivity` etkinliğini kaydeder. Uygulamanın çalışan akışı düğmeyle açılan Android konuşma tanıma ekranıdır.
- `NovaService.kt` kayıtlı veya başlatılan bir servis değildir. Arka plan mikrofonu ve ayar ekranı açma kodu üretimde kullanılmaz; bu derleme arka plan dinleme özelliği vaat etmez.
- Uygulama kendi sunucusuna veya bir yapay zekâ API'sine bağlanmaz. Ses tanıma ve TTS, cihazdaki sağlayıcılara bağlıdır.
- Boş tanıma sonucu, Türkçe TTS bulunmaması, mikrofon izni reddi ve tanıma etkinliğinin bulunmaması artık kullanıcıya açıklanır. Türkçe komutlar Türkçe yerel ayarıyla normalleştirilir.
- Kaynak arşivindeki workflow da SDK kurulumunun varsayılan eski `tools` paketini kullanıyordu. Tek etkin workflow `.github/workflows/setup.yml` dosyasıdır; SDK paketleri açıkça belirtilir ve hatalar gizlenmez.

Otomatik doğrulama, komut birim testleri ve API 35 emülatöründe temel uygulama/izin akışını kapsar. Gerçek mikrofondan Türkçe konuşmanın doğruluğu, fiziksel cihazlardaki ses motorları, kilit ekranı veya sürekli dinleme bu testlerin kapsamı değildir.
