# Özgün kaynak arşivi incelemesi

NovaAndroid-source.zip değiştirilmeden saklanır. Arşiv NovaAndroid/ altında iki Kotlin sınıfı, manifest, Gradle dosyaları ve bir workflow içerir; Gradle Wrapper içermez.

Özgün sürümde MainActivity yalnızca harici Android tanıma ekranını açıyor, birkaç sabit komuta yanıt veriyor ve motor hazırsa TTS çağırıyordu. NovaService manifestte kayıtlı değildi ve başlatılmıyordu; bu sınıftaki arka plan dinleme kodu etkin bir özellik değildi. Ekran ile servis farklı tercih adları/veri tipleri kullanıyordu. Gerçek konuşma-sonuç-yanıt-seslendirme zinciri önceki temel testlerle doğrulanmıyordu.

0.2 sürümünde:

- Eski kullanılmayan NovaService/NovaCommands sınıfları etkin kaynaktan kaldırıldı; özgün kod arşiv ve Git geçmişinde korunur.
- Foreground VoiceInput ve VoiceOutput, Android konuşma/TTS hizmetlerinin gerçek olaylarını yönetir. Dinleme ve seslendirme zaman aşımı/iptal/kurulum durumları görünürdür.
- MainActivity yazı ve tanınan konuşmayı aynı AssistantBrain üzerinden işler. Notlar, sohbet, ses ayarları ve asistan adı nova_v2 tercih alanında tutarlıdır.
- NovaScreen, konuşma kartları, klavye girişi, durumlar, öneriler ve ses kurulumuna erişim sunar.
- Cihaz testleri yalnızca yok-hizmet durumunu değil, konuşma sonucu → komut → TTS isteği ve sesin başlama/bitiş olaylarını da sınar. Test motorları fiziksel ses kalitesi iddiası değildir.
- Tek etkin workflow .github/workflows/setup.yml dosyasıdır. Eski tools paketi istenmez; SDK paketleri belirtilir, wrapper doğrulanır, testler ve APK imzası kontrol edilir.

Cihazın mikrofonu, gerçek Türkçe tanıma doğruluğu ve gerçek TTS sesi ayrıca cihaz üzerinde denenmelidir. Bu sürümde genel amaçlı yapay zekâ API'si veya sürekli arka plan dinlemesi yoktur.
