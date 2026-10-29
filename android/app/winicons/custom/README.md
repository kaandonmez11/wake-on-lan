# Kendi Windows güç ikonların

Bu klasöre şu adlarla SVG koyarsan uygulama o ikonu kullanır (derlemede otomatik):

| Dosya | Buton |
|---|---|
| `lock.svg` | Kilitle |
| `sleep.svg` | Uyku |
| `hibernate.svg` | Hazırda |
| `restart.svg` | Yeniden |
| `shutdown.svg` | Kapat |

Koymadığın ikonlar için sıra: Windows'taki `SegoeIcons.ttf` → yoksa `../fallback` (Fluent UI, MIT).
SVG kuralları ve ayrıntı: kök `README.md` › "Windows güç ikonları".

Buradaki `*.svg` dosyaları **git'e girmez** (`.gitignore`): lisansı belirsiz ikonlar yanlışlıkla paylaşılmasın.
