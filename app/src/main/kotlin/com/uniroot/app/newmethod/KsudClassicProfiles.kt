package com.uniroot.app.newmethod

import android.content.Context
import java.io.File

/**
 * Profils ksud CLASSIC de la new méthode — le miroir de KsudNextProfiles
 * pour la flavor KernelSU classic.
 *
 * DEUX SOURCES :
 *  1. EMBARQUÉ — le ksud universal de l'APK (df/ksud-new-classic), toujours
 *     présent, id "bundled-universal".
 *  2. DYNAMIQUE — filesDir/ksud-classic-latest : le ksud téléchargé + patché
 *     par DfKsudUpdater (bouton « Update ksud classic »). Le tag de la
 *     release vit dans ksud-classic-latest.tag. Un seul slot dynamique : un
 *     nouveau téléchargement remplace le précédent.
 *
 * DFBridge.stageKsud stage selectedFile() si le profil choisi est dynamique,
 * sinon l'asset embarqué.
 */
object KsudClassicProfiles {

    private const val PREFS = "uniroot_prefs"
    private const val PREF_KEY = "ksud_classic_profile"
    private const val MODE = Context.MODE_PRIVATE

    data class ClassicProfile(
        val id: String,       // "bundled-universal" ou la version ("3.3.0")
        val label: String,    // affichage bande
        val note: String,     // description
        val dynamic: Boolean, // true = téléchargé (ksud-classic-latest)
    )

    fun latestFile(context: Context): File = File(context.filesDir, "ksud-classic-latest")

    private fun markerFile(context: Context): File = File(context.filesDir, "ksud-classic-latest.tag")

    /** "3.3.0-32601" — written by the updater, used as the profile label. */
    private fun nameFile(context: Context): File = File(context.filesDir, "ksud-classic-latest.name")
    private fun noteFile(context: Context): File = File(context.filesDir, "ksud-classic-latest.note")

    private fun latestName(context: Context): String? =
        nameFile(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun latestTag(context: Context): String? =
        markerFile(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Profils classic : le dynamique (s'il existe) d'abord, puis l'embarqué. */
    fun list(context: Context): List<ClassicProfile> {
        val out = ArrayList<ClassicProfile>()
        val tag = latestTag(context)
        if (tag != null && latestFile(context).isFile) {
            // Profile id = "version-5-digit-code" (e.g. "3.3.0-32601"),
            // fallback on the bare version when the name file is absent.
            val name = latestName(context) ?: tag.removePrefix("v")
            out.add(ClassicProfile(
                id = name,
                label = name,
                note = noteFile(context).takeIf { it.isFile }?.readText()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "official ksud $tag patched on device",
                dynamic = true))
        }
        out.add(ClassicProfile(
            id = "bundled-universal",
            label = "Bundled universal",
            note = "the ksud bundled in the APK",
            dynamic = false))
        return out
    }

    fun selectedId(context: Context): String =
        context.getSharedPreferences(PREFS, MODE).getString(PREF_KEY, "") ?: ""

    /** Sélection persistée, sinon le premier de la liste. */
    fun selected(context: Context): ClassicProfile? =
        list(context).firstOrNull { it.id == selectedId(context) } ?: list(context).firstOrNull()

    fun setSelected(context: Context, id: String) {
        context.getSharedPreferences(PREFS, MODE).edit().putString(PREF_KEY, id).apply()
    }

    /**
     * Fichier à stager pour le profil choisi — null = profil embarqué
     * (DFBridge stage alors l'asset df/ksud-new-classic).
     */
    @JvmStatic
    fun selectedFile(context: Context): File? {
        val p = selected(context) ?: return null
        return if (p.dynamic && latestFile(context).isFile) latestFile(context) else null
    }

    /** Enregistre le résultat de l'updater et le SÉLECTIONNE. */
    fun selectLatest(context: Context, name: String, note: String) {
        nameFile(context).writeText(name)
        noteFile(context).writeText(note)
        setSelected(context, name)
    }

    /** Supprime le ksud téléchargé (fichier + tag) et rebascule sur l'embarqué. */
    fun deleteLatest(context: Context): Boolean {
        val f = latestFile(context)
        val existed = f.isFile
        f.delete()
        markerFile(context).delete()
        nameFile(context).delete()
        noteFile(context).delete()
        File(context.filesDir, "ksud-classic-latest.target").delete()
        setSelected(context, "bundled-universal")
        return existed
    }
}
