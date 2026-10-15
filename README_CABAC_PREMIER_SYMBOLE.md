# CABAC : premier élément de syntaxe réel

## Explication simple

Le lecteur Java décode maintenant le drapeau mb_skip_flag du premier macrobloc de chaque slice P de la vidéo WhatsApp fournie. Ce drapeau indique si ce macrobloc est sauté. Les valeurs Java concordent avec le décodage FFmpeg : **706 comparaisons, dont 106 drapeaux égaux à un, zéro écart**, sur 709 images au total (les trois images I ne sont pas concernées).

Cette validation porte sur un seul élément de syntaxe par slice P. Elle ne prouve pas encore le décodage complet des macroblocs ni la reconstruction exacte du flux CABAC. Aucun nouveau gain .ivo n’est annoncé ; le meilleur conteneur précédent reste **5 414 825 octets**.

## Partie technique

CabacFirstSkip lit les champs des en-têtes exposés par trace_headers. Il vérifie leurs bits à leurs positions dans les données RBSP des SPS, PPS et slices du flux Annex B. Un en-tête altéré est rejeté avant la lecture CABAC. Ce contrôle lie les champs utilisés aux octets testés ; il ne remplace pas un parseur autonome des en-têtes.

Le domaine pris en charge est volontairement étroit : SPS/PPS identiques et d’identifiant zéro, vidéo progressive à huit bits de luminance, CABAC, aucun groupe de slices, première adresse de macrobloc zéro, une slice par image, images I/P, cabac_init_idc=1 pour les slices P. Les autres configurations sont rejetées.

Pour chaque slice P :

```
SliceQPY = 26 + pic_init_qp_minus26 + slice_qp_delta
ctxIdx = 11
(m,n) = (22,25) pour cabac_init_idc=1 et ctxIdx=11
pre = Clip3(1,126, ((22*SliceQPY) >> 4) + 25)
```

Le macrobloc est en position (0,0), donc les voisins gauche et supérieur sont indisponibles. Les deux contributions au contexte sont nulles. Le drapeau est la première décision régulière de cette slice progressive P. L’état et le bit majoritaire viennent de la formule d’initialisation déjà testée dans CabacArithmetic ; le lecteur applique ensuite une décision régulière et s’arrête.

La référence indépendante est le journal mb_type du décodeur FFmpeg à un thread, sur le même flux Annex B. Le programme contrôle la taille entière de chaque grille de macroblocs et compare le premier marqueur de la première ligne. Le marqueur S correspond à un macrobloc sauté. Les images éventuellement décodées pendant la recherche des informations du flux sont exclues en ne considérant que le décodage après Stream mapping. Les comptes, les types I/P et l’adresse zéro sont contrôlés pour établir la correspondance. Cette méthode ne constitue pas une trace indépendante de tous les bins CABAC, ni une validation séparée de chaque état arithmétique interne.

Le CSV conserve le QP, l’état initial, le bit majoritaire, les deux résultats et l’état arithmétique après décision. bits_read est une position absolue dans la NAL RBSP, et non une taille de compression.

## Validation réellement exécutée

- 709 images associées aux 709 slices ; 706 drapeaux P comparés à FFmpeg 6.1.1 ; 106 égaux à un ; zéro écart.
- Altération du bit d’en-tête first_mb_in_slice : rejet pour divergence entre trace et source.
- Altération du premier marqueur P de la référence : rejet pour divergence du drapeau décodé.

Les résultats, les SHA-256 de la vidéo et du flux exporté et le journal de référence sont dans research-results/2026-10-04/mp4-cabac-first-skip. La trace d’en-tête originale est conservée dans le dossier mp4-slice-headers.

## Reproduire

Java 17 et FFmpeg sont nécessaires. Utiliser des chemins absolus :

```bash
bash test-cabac-first-skip.sh /chemin/original.mp4 /chemin/resultats
```

Ce script compile les expériences, exécute les tests existants, exporte le flux élémentaire sans réencodage, produit les deux journaux de référence puis compare les drapeaux. Il laisse source.h264 et les rapports dans le dossier de résultats. Il ne fabrique pas de conteneur .ivo et ne modifie pas le MP4.

Sources primaires recoupées pour le contexte et les constantes : H.264 clause 9.3, https://www.itu.int/rec/T-REC-H.264 ; https://www.ffmpeg.org/doxygen/4.2/h264__cabac_8c_source.html (initialisation du contexte 11 et decode_cabac_mb_skip). Signification des marqueurs de référence : https://www.ffmpeg.org/doxygen/5.1/mpegutils_8c_source.html . Aucun code C de ces fonctions n’a été porté dans cette expérience ; les constantes numériques et les règles normatives guident le lecteur Java.

## Étape suivante

Lire les macroblocs sautés consécutifs du début de slice, avec end_of_slice_flag et contextes spatiaux adaptés, puis s’arrêter au premier macrobloc non sauté. Comparer chaque drapeau et chaque frontière à une référence. Le décodage des types de macroblocs, mouvements et résidus et le réémetteur exact restent ensuite nécessaires avant de mesurer des gains CABAC.
