# Mise à jour vitesse JPEG — 9 octobre 2026

`compress` **et** `compress-fast` utilisent désormais le même portefeuille JPEG optimisé. `compress-compact` l’utilise également avec le stockage mémoire compact. `compress-enhanced` garde les essais exhaustifs.

Ouvrir le `pom.xml` à la racine avec un JDK 17 ou supérieur. En terminal Linux : `bash build.sh`. Pour compresser : `java -cp target/classes io.ivo.cli.IvoCli compress-fast photo.jpg photo.ivo`.

Les chiffres détaillés et limites sont dans `README_VITESSE_2026_10_09.md`.

Mise à jour du 8 octobre 2026 : `compress` sélectionne désormais automatiquement les candidats de recherche validés. Voir [README_JPEG_AUTO_2026_10_08.md](README_JPEG_AUTO_2026_10_08.md) pour les mesures sur la photo Fold5 et les modes explicites.

# Stockage compact optionnel — version 29

`compress-compact`, `bench-compact` et `decompress-compact` réduisent les tables rapides du mélangeur sans changer les probabilités ni le format. Les 32 bits restent par défaut : la réduction des allocations ne garantit pas une accélération. Voir [README_COMPACT_MIXER.md](README_COMPACT_MIXER.md).

# Taille, temps et mémoire — version 28

Un mode rapide explicite et un benchmark séparant compression et décompression sont ajoutés. Lire [README_PERFORMANCE_FAST.md](README_PERFORMANCE_FAST.md) pour le protocole, les résultats et les limites.

# JPEG progressifs : mélange adaptatif — version 27

Six fichiers progressifs gagnent encore 26 274 octets (6,296 % de la taille IVO précédente). Les 48 nouvelles archives sont exactes et les pixels contrôlés indépendamment sur les six fichiers. JPS2 et les profils antérieurs sont disponibles dans `compress-enhanced`. Voir [README_JPEG_PROGRESSIVE_MIXER.md](README_JPEG_PROGRESSIVE_MIXER.md).

# JPEG : mélange de contextes — version 26

Six JPEG naturels gagnent encore 23 436 octets (2,036 % de la taille IVO précédente). Les 60 archives expérimentales sont restituées exactement. Les nouveaux profils sont disponibles dans `compress-enhanced`. Voir [README_JPEG_CONTEXT_MIXER.md](README_JPEG_CONTEXT_MIXER.md).

# Intégration consolidée — version 25

`compress-enhanced` compare maintenant les moteurs utilisateur aux moteurs habituels et conserve le plus petit résultat exact. MP4 et MP3 retrouvent les archives de version 24 ; neuf anciennes archives restent lisibles. Voir [README_USER_CODE_INTEGRATION.md](README_USER_CODE_INTEGRATION.md) pour les commandes, limites et prochaines pistes MP3/JPEG.

# Code utilisateur — version 24

Intégration expérimentale MP4 / MP3 / WebP : 11,974 % gagnés sur la vidéo WhatsApp et dix nouvelles archives restituées exactement. La sélection automatique reste inchangée. Voir [README_USER_CODE_REVIEW.md](README_USER_CODE_REVIEW.md) pour les mesures, limites et commandes.

# CABAC — version 23

Premiers résidus inter P lus et comparés : 860 macroblocs, 103 272 positions de coefficients, zéro écart. Reconstruction diagnostique de 101 981 bits du préfixe. Aucun nouveau gain CABAC ni promotion automatique. Voir [README_CABAC_RESIDUS.md](README_CABAC_RESIDUS.md).

# Remesure MP4 — version 22

Résultat reproduit : 5 414 803 octets, 22 octets supplémentaires gagnés, dix archives exactes. Aucun changement des modèles ni du routeur. Voir [README_MP4_REMESURE_22.md](README_MP4_REMESURE_22.md).

# Validation externe JPEG et MP3 — version 21

Modèles de version 20 inchangés. Quatre nouvelles images JPEG progressives gagnent 2,815 % ; l’énergie MP3 gagne sur sept des dix nouvelles sources, avec sélection du meilleur ancien ou nouveau modèle. Les 62 candidats et 22 références sont restitués exactement. Voir [README_VALIDATION_PALIER_21.md](README_VALIDATION_PALIER_21.md). Le portefeuille automatique reste celui de version 19.

# Recherche JPEG / MP3 / MP4 — version 20

Nouveaux essais exacts : JPEG progressifs/multiscans (gain de 3,385 % sur cinq fichiers externes), contexte d’énergie MP3 (559 765 octets sur Tao), corps vidéo MP4 (22 octets sur WhatsApp). Les 98 nouvelles archives sont vérifiées. Voir [README_RECHERCHE_PALIER_20.md](README_RECHERCHE_PALIER_20.md). Les nouveaux codecs restent expérimentaux ; le portefeuille automatique de version 19 est conservé.

# Intégration JPEG automatique — 4 octobre 2026, version 19

Le CLI habituel compare maintenant le portefeuille JPEG de frontière validé (modes 9 et 10) aux anciens moteurs et conserve le plus petit conteneur complet. La reconstruction est vérifiée avant sélection. Voir [README_INTEGRATION_JPEG.md](README_INTEGRATION_JPEG.md) pour les commandes, validations et limites. Les mentions de recherche seule dans les sections antérieures décrivent leurs versions historiques.

# Sous-partitions CABAC

Voir [README_CABAC_SOUS_TYPES.md](README_CABAC_SOUS_TYPES.md) : 256 sub_mb_type concordent directement avec un décodeur FFmpeg instrumenté ; les quatre formes sont couvertes. Lecture partielle, aucune nouvelle mesure de compression.

# Types de macroblocs CABAC

Voir [README_CABAC_TYPES_MB.md](README_CABAC_TYPES_MB.md) : 717 catégories/partitions du premier macrobloc non sauté concordent avec FFmpeg. Lecture partielle ; aucun nouveau gain .ivo.

# Préfixes CABAC sur vidéo réelle

Voir [README_CABAC_PREFIXES.md](README_CABAC_PREFIXES.md) : 1 077 drapeaux de saut et 360 décisions de terminaison contrôlés sur trois vidéos. Aucun nouveau gain ; lecture limitée au préfixe initial de macroblocs sautés.

# Premier élément CABAC sur vidéo réelle

Voir [README_CABAC_PREMIER_SYMBOLE.md](README_CABAC_PREMIER_SYMBOLE.md) : 706 premiers drapeaux de saut de slices P concordent avec FFmpeg, dont 106 égaux à un. Domaine restreint ; ni décodeur complet, ni nouveau gain .ivo.

# Décisions régulières CABAC

Voir [README_CABAC_REGULIER.md](README_CABAC_REGULIER.md) : primitives régulières et initialisation générique des contextes validées sur des séquences synthétiques. Aucun nouveau gain de compression ; décodage des macroblocs et réémission encore à construire.

# Préparation CABAC en cours

Voir [README_CABAC_PREPARATION.md](README_CABAC_PREPARATION.md) : 709 initialisations de slices contrôlées, flux élémentaire restauré exactement et primitives arithmétiques testées. Pas encore de décodage/réémission complète CABAC ; aucun nouveau gain .ivo.

# Dernier essai : préfixes de slices H.264

Voir [README_MP4_SLICES.md](README_MP4_SLICES.md) : seize archives exactes ; 580 octets supplémentaires sur la vidéo originale. Analyse guidée par FFmpeg, décompression Java autonome ; pas encore une IR interne CABAC complète.

# Dernier essai MP4 : V5, groupes NAL

Voir [README_MP4_NAL_TYPES.md](README_MP4_NAL_TYPES.md) : 40 archives exactes ; deux petits gains synthétiques, aucun gain sur le fichier WhatsApp original. L’ancien meilleur candidat est conservé.

# Dernier essai MP4 : V4 NAL

Voir [README_MP4_NAL.md](README_MP4_NAL.md) : 30 archives exactes sur cinq cas, quatre améliorations modestes ; 458 octets gagnés sur la vidéo originale. Candidat de recherche, compression automatique désactivée ; lecture des archives intégrée.

# MP39/MP40 intégrés au runtime — 4 octobre 2026

Le routeur compare désormais MP31, MP39 et MP40 au coût du conteneur complet. Le CLI principal décompresse les archives de types 18 et 19 déjà produites. Voir [README_INTEGRATION_MP3.md](README_INTEGRATION_MP3.md) pour la validation et les limites. Les notes de recherche ci-dessous décrivent les étapes antérieures ; leurs mentions de MP31 seul sont historiques.

# Dernière recherche : MP40, contextes causaux MP3

Voir [README_MP3_CONTEXTES.md](README_MP3_CONTEXTES.md) : 104 archives exactes, quatre améliorations sur treize fichiers. MP39 reste candidat et le CLI principal garde MP31.

# Projet complet : démarrage

Lire [DEMARRER_ICI.md](DEMARRER_ICI.md) : tableau des formats, commandes Maven et MP39, limites et état des moteurs.

# Dernière recherche : MP39, cordes spectrales

Voir [README_MP3_CORDES.md](README_MP3_CORDES.md) pour les mesures complètes et la restitution exacte. Candidat de recherche ; le CLI principal conserve MP31.

# Dernière recherche MP3

Voir [README_MP3_SPECTRAL.md](README_MP3_SPECTRAL.md) : parseur spectral exact, 13 fichiers et contrôle indépendant ; compression provisoire non promue.

# Dernier essai MP3

Voir [README_MAIN_LANES.md](README_MAIN_LANES.md) pour MP37 et ses mesures réelles.

Dernier essai MP3 : [README_BLOCK_CONTEXT.md](README_BLOCK_CONTEXT.md), MP36 avec cordes par type de bloc/canal. Le CLI principal garde MP31.

Dernière expérience MP3 : [README_SIDE_FIELDS.md](README_SIDE_FIELDS.md), candidat MP35 exact avec cordes de champs et prédicteurs. Le CLI principal reste MP31.

État MP3 actuel : voir [README_RESERVOIR.md](README_RESERVOIR.md) pour l’extension MPEG-2/2.5, ses mesures et les commandes de recherche. Le CLI normal garde MP31.

# IVO — candidat JPEG figé, 3 octobre 2026

## Explication simple

Le programme conserve exactement le fichier JPEG original : pixels, métadonnées et octets du flux. Il essaie quatre représentations JPEG puis choisit la plus petite. Le routeur général compare les conteneurs .ivo complets et garde STORE si les autres candidats ne sont pas rentables.

Cette version est un candidat de recherche figé, pas une preuve de prise en charge de tous les JPEG. Le moteur spécialisé couvre le JPEG baseline Huffman pris en charge par le parseur. Les formats non pris en charge peuvent revenir à STORE. Les images progressives, arithmétiques et certains cas particuliers ne sont pas traités par ce moteur.

Java 17 requis. Aucun JAR précompilé n'est distribué.

```bash
bash build.sh
java -jar target/ivo-0.8.0-research.jar compress photo.jpg photo.ivo
java -jar target/ivo-0.8.0-research.jar decompress photo.ivo photo-restauree.jpg
```

Alternative : `mvn test package`, puis le JAR Maven `target/ivo-0.8.0-research.jar`.

## Partie technique

Pour chaque coefficient AC non nul v : m=abs(v), k=floor(log2(m))+1, s=1 si v<0, t=m-2^(k-1). Reconstruction : v=(-1)^s*(2^(k-1)+t).

A code k ; B code s ; C code les k-1 bits de t. Les cordes DC, profondeur et présence sont conservées, ainsi que les métadonnées, la disposition et le patch nécessaire pour reconstruire le flux entropique original.

Le portefeuille compilé conserve JV67 (ABC), JV72 (mélange spatial/spectral), JV74 (mélange directionnel), JV83 (ressemblance avec qualité). Les autres variantes et les extensions B/C restent dans research-experiments, hors compilation Maven. La sélection interne minimise la taille du payload : le moteur et les autres champs du conteneur étant identiques, son classement équivaut au classement par taille finale. Le routeur général mesure effectivement chaque conteneur complet.

JV83 ajoute un cinquième expert de catégories. Six voisins causaux sont comparés à l'aide des quatre fréquences précédentes. La distance est la somme des écarts absolus entre catégories. Le meilleur voisin est choisi avec un départage déterministe. Qualité forte : distance nulle avec observations ; moyenne : distance <=2*n, où n est le nombre de fréquences observées ; faible : autres cas. Les poids des cinq experts et les contextes de l'expert de ressemblance sont séparés selon la qualité. Aucun pointeur de bloc n'est transmis.

Les fréquences initiales valent 1, chaque observation ajoute 2, et les comptes sont divisés par deux avec minimum 1 au-delà de 16384. Poids entiers Q16, mise à jour bayésienne, redistribution fixe de 1/256 vers l'uniforme. Les signes et les queues conservent les modèles B/C existants.

Invariants : dépendances causales identiques à l'encodage et au décodage ; encode/decode de chaque candidat vérifié par Arrays.equals ; contrôle SHA-256 du conteneur ; préservation du patch entropique. Le checksum détecte une différence, il ne remplace pas la construction réversible.

Les briques sont apparentées à PPM, au mélange d'experts et à la prédiction spatiale. L'originalité de leur combinaison n'est pas établie par ces mesures.

## Validation et mesures

Les journaux dans research-results/2026-10-03 contiennent les tailles réellement mesurées, les variantes rejetées et la validation du portefeuille figé sur les 42 JPEG disponibles. Les fichiers externes ne sont pas redistribués. Le corpus indépendant provient de ianare/exif-samples, commit 5332a9cf0220e9c6c93f88a187daa90808051e10 : racine jpg, hdr et mobile, exclusion préalable du fichier corrupted.jpg et des doublons SHA-256 déjà testés.

Pour vérifier vos propres JPEG uniquement avec STORE et le portefeuille figé :

```bash
java -Xmx1024m -cp target/classes:target/test-classes io.ivo.jpeg.FrozenCorpusCheck photo1.jpg photo2.jpg
```

La validation sur un corpus fini ne prouve pas l'universalité. Brunsli reste meilleur sur les 15 fichiers où il a été comparé.

## Reprise MP3

Le moteur MP3 existant et ses outils de recherche restent présents. La nouvelle étape commence par l'analyse structurelle du livre audio fourni. Les gains mesurés sur les seules informations latérales ne sont pas annoncés comme gains du fichier .ivo complet. Le prototype sémantique antérieur ne prend en charge que MPEG-2/2.5 mono ; le fichier fourni est stéréo à 48 kHz et nécessite une extension spécifique.
