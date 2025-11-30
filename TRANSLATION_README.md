# Translation Status - Deutsia Radio

## ✅ Ready for Translation

The project is now fully prepared for community translations! Here's what has been set up:

### 📁 Translation Files Created

Template translation files have been created for the following languages:

| Language | Code | Directory | Status |
|----------|------|-----------|--------|
| **Spanish** | `es` | `app/src/main/res/values-es/` | 🔴 Needs Translation |
| **French** | `fr` | `app/src/main/res/values-fr/` | 🔴 Needs Translation |
| **German** | `de` | `app/src/main/res/values-de/` | 🔴 Needs Translation |
| **Portuguese** | `pt` | `app/src/main/res/values-pt/` | 🔴 Needs Translation |
| **Russian** | `ru` | `app/src/main/res/values-ru/` | 🔴 Needs Translation |
| **Chinese (Simplified)** | `zh` | `app/src/main/res/values-zh/` | 🔴 Needs Translation |
| **Japanese** | `ja` | `app/src/main/res/values-ja/` | 🔴 Needs Translation |
| **Italian** | `it` | `app/src/main/res/values-it/` | 🔴 Needs Translation |
| **Arabic** | `ar` | `app/src/main/res/values-ar/` | 🔴 Needs Translation |

### 📊 Translation Statistics

- **Total Strings**: 334 in base `strings.xml`
- **Hardcoded Strings**: ~70 need extraction (see TRANSLATION_GUIDE.md)
- **Completion**: 0% (English base only)

## 🚀 Quick Start for Translators

### 1. Choose Your Language

Navigate to the appropriate directory:
```bash
cd app/src/main/res/values-{language_code}/
```

### 2. Edit strings.xml

Open `strings.xml` and translate the text between XML tags:

**✅ Correct:**
```xml
<!-- English -->
<string name="tab_radios">Library</string>

<!-- Spanish -->
<string name="tab_radios">Biblioteca</string>
```

**❌ Wrong:**
```xml
<!-- DON'T change the name attribute! -->
<string name="tab_biblioteca">Biblioteca</string>
```

### 3. Important Rules

- ✅ **Translate**: Text content between tags
- ❌ **Don't Translate**:
  - `name` attributes
  - "deutsia radio" (brand name)
  - "Orbot", "Material You" (proper nouns)
  - Technical terms: SOCKS, HTTP, I2P, Tor
  - Format placeholders: `%s`, `%d`, `%1$s`

### 4. Formatting

Keep these intact:
- **Placeholders**: `%s` → string, `%d` → number
- **Escaped characters**: `don\'t` (apostrophe), `&amp;` (ampersand)
- **XML entities**: `&lt;`, `&gt;`, `&quot;`

**Example:**
```xml
<!-- English -->
<string name="station_saved">%s added to library</string>

<!-- French -->
<string name="station_saved">%s ajoutée à la bibliothèque</string>
```

## 📚 Full Documentation

See **[TRANSLATION_GUIDE.md](TRANSLATION_GUIDE.md)** for:
- Complete hardcoded strings list (need extraction)
- Detailed translation guidelines
- Testing instructions
- Android localization best practices
- Contribution workflow

## ⚠️ Known Issues

### Hardcoded Strings Pending Extraction

Approximately **70 strings** are currently hardcoded in Kotlin source files and need to be moved to `strings.xml`. These include:

- Tor status messages (TorStatusView.kt)
- Tor connection dialog strings (TorQuickControlBottomSheet.kt)
- Custom proxy status messages
- Genre list (32 genres in LibraryFragment.kt)
- Proxy type labels

**See TRANSLATION_GUIDE.md Section: "Hardcoded Strings Requiring Extraction"** for the complete list.

Once these are extracted, translators will need to add them to their translation files.

## 🛠️ How to Add a New Language

Don't see your language? Add it:

```bash
# Create directory
mkdir -p app/src/main/res/values-{locale_code}

# Copy base template
cp app/src/main/res/values/strings.xml app/src/main/res/values-{locale_code}/

# Start translating
nano app/src/main/res/values-{locale_code}/strings.xml
```

**Common locale codes**: `ko` (Korean), `tr` (Turkish), `pl` (Polish), `nl` (Dutch), `hi` (Hindi), `sv` (Swedish), `no` (Norwegian), `fi` (Finnish), `da` (Danish)

## 🧪 Testing Your Translation

1. **Build the app** with Android Studio
2. **Change device language**: Settings → System → Languages
3. **Verify**:
   - All text displays correctly
   - No text overflow or truncation
   - Placeholders render properly (`%s` shows actual values)
   - Special characters display correctly

## 🤝 Contributing Your Translation

1. Fork the repository
2. Create a branch: `git checkout -b translation-{locale}`
3. Translate your `strings.xml`
4. Test thoroughly
5. Commit: `git commit -m "Add {language} translation"`
6. Push: `git push origin translation-{locale}`
7. Open a Pull Request

## 📞 Need Help?

- **Translation questions**: Open an issue with `[Translation]` tag
- **Technical issues**: See [TRANSLATION_GUIDE.md](TRANSLATION_GUIDE.md)
- **Android localization**: [Official Android Docs](https://developer.android.com/guide/topics/resources/localization)

## 🎯 Translation Priority

### High Priority
1. UI labels and buttons (navigation, actions)
2. Error messages and notifications
3. Settings screen
4. Dialogs and prompts

### Medium Priority
5. Status messages
6. Descriptions and help text
7. About section

### Low Priority
8. Technical strings (may not need translation)
9. Debug messages

---

**Thank you for helping make Deutsia Radio accessible to users worldwide! 🌍**

**Translation Progress Tracking**: Update this README with ✅ when your language is complete!
