# CABAC — préparation de la recherche, pas un compresseur prêt

## Explication simple

Cette étape prépare le traitement des données internes H.264. Sur la vidéo WhatsApp, le programme contrôle 709 slices et conserve exactement les octets d'échappement du flux vidéo élémentaire exporté. Les premières primitives arithmétiques Java (initialisation, bypass, terminaison) passent les contrôles ajoutés.

**Aucun nouveau gain de compression n'est mesuré.** Le meilleur conteneur précédent reste 5 414 825 octets. Ni le décodage complet des symboles CABAC ni leur réémission exacte ne sont encore réalisés. Ces sources restent dans les expériences, hors routeur et compilation Maven principale.

La vérification exacte porte ici sur le flux H.264 Annex B exporté par FFmpeg, pas sur la reconstruction d'un nouveau conteneur MP4 depuis une IR CABAC. Elle ne remplace pas les contrôles précédents du MP4 complet.

## Partie technique

`CabacPreflight` repère les unités Annex B sans éliminer leurs préfixes. Pour chaque unité, il sépare les octets d'emulation prevention 03 des données RBSP en conservant un registre de leurs positions physiques. La reconstruction replace ces octets aux positions enregistrées. Un ledger constitue ici une preuve de réversibilité, pas une représentation comprimée.

Résultat du fichier testé : 715 NAL, 709 slices, 43 octets d'échappement repérés ; égalité exacte du flux élémentaire après suppression/réinsertion. La trace d'en-tête FFmpeg fournit la limite alignée de chaque slice. Les offsets CABAC initiaux observés vont de 5 à 508.

`CabacArithmetic` suit les primitives de H.264 : range=510 à l'initialisation ; offset obtenu sur neuf bits, avec rejet de 510 et 511. Bypass : offset'=2*offset+bit_lu ; bin=I(offset'>=range) puis soustraction de range si bin=1. Terminaison : range'=range-2 ; comparaison avec offset ; si non terminal, renormalisation jusqu'à range>=256 avec lecture des bits requis. Les lectures hors source et opérations après terminaison sont rejetées.

Tests exécutés : 1 020 configurations d'un pas bypass depuis les offsets initiaux légaux ; trois frontières de terminaison ; frontière de renormalisation ; deux offsets initiaux interdits. Cela ne prouve pas le décodage d'une slice réelle : le programme n'applique pas arbitrairement ces primitives aux données réelles, car le calendrier et les contextes doivent venir de la syntaxe H.264.

Référence normative utilisée pour ces primitives : H.264, clauses 9.3.1.2 et 9.3.3.2, https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.264-202408-I%21%21PDF-E&lang=e&type=items . La préparation s'appuie aussi sur la trace FFmpeg ; elle n'est pas un parseur complet propre au projet.

## Étapes manquantes, dans l'ordre

1. Décisions régulières et initialisation générique réalisées : voir README_CABAC_REGULIER.md. La confrontation à un décodeur externe reste à faire.
2. Initialisation des contextes depuis SPS/PPS, slice_type, cabac_init_idc et QP.
3. Calendrier réel de décodage des macroblocs et éléments de syntaxe, avec leurs dépendances spatiales.
4. Réémetteur CABAC et traitement des bits de terminaison, remplissage et octets d'échappement ; vérification de la slice originale octet pour octet.
5. Seulement ensuite : cordes de symboles, N&C temporel et REF+résidu, coût complet et corpus indépendant.

## Reproduire

```bash
bash build-cabac-research.sh
ffmpeg -v error -i original.mp4 -map 0:v:0 -c copy -bsf:v h264_mp4toannexb -f h264 original.h264
ffmpeg -v trace -i original.mp4 -map 0:v:0 -c copy -bsf:v trace_headers -f null - > trace.log 2>&1
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacPreflight original.h264 trace.log initialization.csv
```

Les rapports mesurés sont dans `research-results/2026-10-04/mp4-cabac-preflight`. L'extraction d'en-têtes dépend de FFmpeg ; les sources des primitives et du contrôle des octets sont Java 17. Aucun statut de moteur prêt n'est attribué à cette étape.
