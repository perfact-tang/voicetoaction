package com.vibecodingjapan.ideavox

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

enum class AppLanguage(val languageTag: String, val nativeName: String) {
  ZH("zh-CN", "中文"),
  JA("ja-JP", "日本語"),
  EN("en-US", "English"),
  KO("ko-KR", "한국어"),
  ;

  val locale: Locale get() = Locale.forLanguageTag(languageTag)

  companion object {
    fun fromLanguageTag(tag: String?): AppLanguage {
      val language = tag?.let(Locale::forLanguageTag)?.language
      return entries.firstOrNull { it.locale.language == language } ?: ZH
    }

    fun fromReaderLanguage(language: ReaderLanguage): AppLanguage =
      when (language) {
        ReaderLanguage.ZH -> ZH
        ReaderLanguage.JA -> JA
        ReaderLanguage.EN -> EN
        ReaderLanguage.KO -> KO
      }
  }
}

object AppLanguageManager {
  private const val PREFERENCES = "ideavox_language"
  private const val KEY_LANGUAGE_TAG = "language_tag"

  fun current(context: Context): AppLanguage {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
      if (!locales.isEmpty) return AppLanguage.fromLanguageTag(locales[0]?.toLanguageTag())
    }
    val stored =
      context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE_TAG, null)
    return AppLanguage.fromLanguageTag(stored)
  }

  fun set(context: Context, language: AppLanguage) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_LANGUAGE_TAG, language.languageTag)
      .apply()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.getSystemService(LocaleManager::class.java).applicationLocales =
        LocaleList.forLanguageTags(language.languageTag)
    }
    createLocalizedNotificationChannels(context.applicationContext)
  }

  fun wrap(context: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
    val locale = current(context).locale
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLocales(LocaleList(locale))
    return context.createConfigurationContext(configuration)
  }
}

internal data class Translation(val ja: String, val en: String, val ko: String) {
  fun forLanguage(language: AppLanguage): String =
    when (language) {
      AppLanguage.ZH -> error("Chinese is the source language")
      AppLanguage.JA -> ja
      AppLanguage.EN -> en
      AppLanguage.KO -> ko
    }
}

internal fun localizeText(text: String, language: AppLanguage): String {
  if (language == AppLanguage.ZH || text.isBlank()) return text
  IdeavoxTranslations.phrases[text]?.let { return it.forLanguage(language) }
  var localized = text
  IdeavoxTranslations.sortedPhrases.forEach { (source, translation) ->
    if (localized.contains(source)) {
      localized = localized.replace(source, translation.forLanguage(language))
    }
  }
  return localized
}

fun String.localized(context: Context): String =
  localizeText(this, AppLanguageManager.current(context))

@Composable
fun LocalizedText(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  fontSize: TextUnit = TextUnit.Unspecified,
  fontStyle: FontStyle? = null,
  fontWeight: FontWeight? = null,
  fontFamily: FontFamily? = null,
  letterSpacing: TextUnit = TextUnit.Unspecified,
  textDecoration: TextDecoration? = null,
  textAlign: TextAlign? = null,
  lineHeight: TextUnit = TextUnit.Unspecified,
  overflow: TextOverflow = TextOverflow.Clip,
  softWrap: Boolean = true,
  maxLines: Int = Int.MAX_VALUE,
  minLines: Int = 1,
  onTextLayout: ((TextLayoutResult) -> Unit)? = null,
  style: TextStyle = LocalTextStyle.current,
) {
  androidx.compose.material3.Text(
    text = text.localized(LocalContext.current),
    modifier = modifier,
    color = color,
    fontSize = fontSize,
    fontStyle = fontStyle,
    fontWeight = fontWeight,
    fontFamily = fontFamily,
    letterSpacing = letterSpacing,
    textDecoration = textDecoration,
    textAlign = textAlign,
    lineHeight = lineHeight,
    overflow = overflow,
    softWrap = softWrap,
    maxLines = maxLines,
    minLines = minLines,
    onTextLayout = onTextLayout,
    style = style,
  )
}
