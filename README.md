# 🚀 StellarLauncher

> Lekki launcher Minecrafta napisany w Javie z wbudowanym Discord Rich Presence.

![Java](https://img.shields.io/badge/Java-8+-orange?logo=java)
![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)
![License](https://img.shields.io/badge/License-MIT-green)

---

## 📥 Pobieranie

Pobierz najnowszą wersję z zakładki **[Releases](https://github.com/xAksym/stellar-launcher/releases)**.

**Wymagania:**
- Java 8 lub nowsza — pobierz z [java.com](https://java.com)
- Windows 10/11, macOS lub Linux

---

## ✨ Funkcje

### 🎮 Discord Rich Presence
Launcher automatycznie pokazuje na Discordzie że grasz w Minecrafta. Biblioteka Discord RPC (`discord-rpc.dll` / `.so` / `.dylib`) jest wypakowywana automatycznie przy pierwszym uruchomieniu — nie musisz nic konfigurować.

### 🗂️ Automatyczna struktura folderów
Przy pierwszym uruchomieniu launcher tworzy wszystko w `~/.stellarlauncher/`:

```
~/.stellarlauncher/
├── minecraft/
│   ├── versions/   # wersje gry
│   ├── libraries/  # biblioteki
│   ├── assets/     # zasoby gry
│   └── mods/       # twoje mody
└── natives/        # biblioteki natywne
```

### 👤 Zarządzanie kontami
Launcher zawiera wbudowanego moda **InGameAccountSwitcher** — przełączaj konta premium i offline bezpośrednio w grze bez restartowania launchera.

---

## 🐛 Rozwiązywanie problemów

**Discord RPC nie działa**
- Upewnij się że Discord jest uruchomiony przed odpaleniem launchera
- Sprawdź czy w folderze `~/.stellarlauncher/` pojawiła się biblioteka `discord-rpc.dll` (Windows) lub odpowiednik dla Twojego systemu

**Launcher się nie odpala**
- Sprawdź czy masz zainstalowaną Javę: otwórz terminal i wpisz `java -version`
- Upewnij się że pobierasz właściwy plik `.jar` z Releases

---

## 🤝 Współpraca

Pull requesty mile widziane! Znajdziesz błąd lub masz pomysł? Otwórz Issue.

## 📄 Licencja

[MIT](LICENSE) — możesz używać, modyfikować i dystrybuować.

---

*Stworzone z ❤️ przez [xAksym](https://github.com/xAksym)*