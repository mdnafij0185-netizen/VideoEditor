package com.example.videoeditor

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat

data class FontDef(val key: String, val label: String, val res: Int)

object Fonts {
    val list = listOf(
        FontDef("bebas_neue", "Bebas Neue", R.font.bebas_neue),
        FontDef("anton", "Anton", R.font.anton),
        FontDef("montserrat_xbold", "Montserrat", R.font.montserrat_xbold),
        FontDef("poppins_bold", "Poppins", R.font.poppins_bold),
        FontDef("bangers", "Bangers", R.font.bangers),
        FontDef("permanent_marker", "Marker", R.font.permanent_marker),
        FontDef("righteous", "Righteous", R.font.righteous),
        FontDef("lobster", "Lobster", R.font.lobster),
        FontDef("pacifico", "Pacifico", R.font.pacifico),
        FontDef("dancing_script", "Dancing Script", R.font.dancing_script),
        FontDef("hind_siliguri_bold", "হিন্দ সিলিগুড়ি", R.font.hind_siliguri_bold),
        FontDef("noto_bengali_bold", "নোটো বাংলা", R.font.noto_bengali_bold),
        FontDef("baloo_da2_bold", "বালু দা", R.font.baloo_da2_bold),
        FontDef("galada", "গালাদা", R.font.galada),
        FontDef("atma_bold", "আত্মা", R.font.atma_bold),
        FontDef("mina_bold", "মীনা", R.font.mina_bold),
        FontDef("tiro_bangla", "তিরো বাংলা", R.font.tiro_bangla)
    )

    fun find(key: String): FontDef = list.firstOrNull { it.key == key } ?: list[0]

    fun family(key: String): FontFamily = FontFamily(Font(find(key).res))

    fun typeface(context: Context, key: String): Typeface =
        ResourcesCompat.getFont(context, find(key).res) ?: Typeface.DEFAULT
}
