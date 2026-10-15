# Audit des dix classes Java et intégration ciblée — 6 octobre 2026

Les dix classes fournies compilent sous Java 17. La référence est le projet IVO de version Library 29, modifié le 5 octobre 2026. Le ZIP fourni reste inchangé.

## Décisions par classe

| Classe | Fonction | Résultat et décision |
|---|---|---|
| Ivo | Mélangeur contextuel, codeur arithmétique, recompression WebP VP8 | Déjà intégré. Même payload sur trois WebP ; garder notre variante figée, bornée et compatible avec le stockage compact. |
| Vp8 | Analyse/réémission des partitions VP8 | Déjà intégré. Utilisé dans les trois reconstructions WebP exactes. Le codeur VP8 seul ne garantit pas un flux identique : les corrections conservées par Ivo restent nécessaires. |
| T | Tables VP8 | Identiques à la référence, hors déclaration de package. Aucun gain supplémentaire. |
| Mp3 | Recompression MPEG-1 Layer III | Même payload sur trois fichiers. Garder le moteur existant et ses contrôles de métadonnées. |
| H | Tables Huffman MP3 | Identiques à la référence, hors déclaration de package. |
| C | Tables CABAC H.264 | Identiques à la référence, hors déclaration de package. |
| Mp4 | CABAC + modèle des en-têtes + AAC | Nouvelle disposition de payload malgré la signature M4V2 conservée : remplacement incompatible. Refuse POC type 2, notamment la vidéo WhatsApp. Adapter comme moteur indépendant uniquement pour les cas admissibles. |
| Aac | Analyse, réécriture et modèle des coefficients AAC | Tables de bandes fixes correspondant aux tests à 22,05/24 kHz. Gain exploitable dans des MP4 admissibles ; pas un moteur AAC universel. |
| AT | Tables AAC | Nouveau support utilisé avec Aac ; tables figées et limitées au périmètre validé. |
| PliCm | Ondelette entière + pliage + mélangeur | Réversible sur les cinq images testées, mais aucun avantage global démontré sur les références PNG. Conserver comme prototype de recherche, sans ajout au routeur. |

## Comparaisons MP3 et WebP

Payloads fournis et payloads du moteur déjà intégré identiques octet par octet :

| Fichier | Original | Payload fourni = intégré | Archive de référence complète |
|---|---:|---:|---:|
| synthetic-mono.mp3 | 33 062 | 22 601 | 22 664 |
| synthetic-stereo.mp3 | 33 062 | 23 321 | 23 386 |
| tao-10s.mp3 | 357 362 | 320 090 | 320 146 |
| test.webp | 3 932 | 3 604 | 3 656 |
| kitten.webp | 51 064 | 47 792 | 47 848 |
| tower.webp | 24 970 | 23 888 | 23 943 |

Chaque comparaison a vérifié Arrays.equals sur le fichier entier. Les tailles de payload ne sont pas des tailles d'archives.

## Gain retenu : vidéo H.264 + audio AAC

| Fichier | Original | Meilleur IVO précédent, exhaustif | IVO avec moteur distinct | Gain supplémentaire |
|---|---:|---:|---:|---:|
| av-poc0-24000.mp4, vidéo + sinusoïde synthétiques | 58 028 | 56 153 | 50 867 | 9,41 % |
| speech-22050.mp4, vidéo synthétique + parole réencodée | 96 397 | 91 889 | 87 874 | 4,37 % |
| speech-24000.mp4, vidéo synthétique + parole réencodée | 96 330 | 91 673 | 88 007 | 4,00 % |

Ces valeurs sont celles de conteneurs IVO complets, même nom de fichier conservé. Le gain de la première ligne arrondi à deux décimales est calculé dans le CSV. La parole provient de l'extrait tao-10s déjà présent dans le projet ; le réencodage AAC sert à produire des entrées valides, puis la recompression IVO conserve exactement ces fichiers. Il ne préserve pas le MP3 avant réencodage. C'est un petit corpus de recherche, pas une preuve de gain sur tous les MP4.

Le nouveau moteur est essayé en mode ENHANCED seulement. Il ne remplace aucun moteur antérieur. STANDARD, FAST et COMPACT gardent leur sélection précédente, tout en sachant décoder le nouvel identifiant. ENHANCED choisit le minimum des conteneurs exacts ; cela peut prendre plus de temps sur les entrées admissibles. Les mesures de temps des prototypes dans les logs incluent des exécutions sans protocole statistique d'échauffement : elles ne démontrent aucune accélération.

La vidéo WhatsApp réelle (5 427 643 octets, audio AAC 44,1 kHz stéréo) est refusée par le prototype fourni avec `poc type`. Elle n'est pas admissible pour le nouveau moteur ; le moteur actuel reste utilisé. Le résultat antérieur de 4 777 717 octets figure dans la référence version 29 ; il n'est pas présenté comme un nouveau benchmark dans cet audit.

Le prototype fourni refuse aussi terminal.mp4 et transform8.mp4 (POC type 2). Sur poc0.mp4 et bframes.mp4, il est exact mais n'améliore pas le meilleur portefeuille précédent. Sur av-poc0-44100.mp4, il est exact mais reste moins bon que le portefeuille précédent ; cette entrée est exclue du nouveau moteur par le filtre de fréquence.

## Pliage : gain local, pas encore avantage face aux références

Les nombres PliCm sont des payloads de pixels gris, sans dimensions ni conteneur. PNG conserve les mêmes pixels gris ; ce n'est pas la recompression exacte du PNG d'origine.

| Image | Pixels bruts | Ondelette seule | Ondelette + pliage | zlib des pixels | PNG |
|---|---:|---:|---:|---:|---:|
| Photo grise dérivée de 19414.jpg, 256×192 | 49 152 | 28 641 | 26 215 | 26 936 | 21 317 |
| Dégradé avec formes, 128×128 | 16 384 | 5 605 | 4 040 | 895 | 463 |
| Même image tronquée, 127×125 | 15 875 | 5 643 | 4 076 | 886 | 447 |
| Bruit, 128×128 | 16 384 | 19 648 | 19 569 | 16 395 | 16 580 |
| Noir, 128×128 | 16 384 | 6 | 6 | 39 | 95 |

Les quinze exécutions (trois modes × cinq images) reconstruisent les pixels exactement. Le pliage diminue le coût face aux ondelettes seules de 8,47 % sur la photo ; son payload reste 22,98 % plus gros que le PNG. Le mode miroir-contextuel ne montre pas de gain significatif. Le cas noir est favorable au mélangeur, mais il ne suffit pas à promouvoir un format et ne compte pas les dimensions, le nom, l'intégrité et le conteneur.

## Couverture AAC

Le parseur fourni n'utilise pas AudioSpecificConfig pour choisir ses tables. Sur une sinusoïde à 44,1 kHz encodée en AAC, il ne réécrit exactement qu'une trame sur 88 (5 octets sur 24 496), notamment à cause du codebook 13 non pris en charge. À 32 kHz, aucune des 64 trames ; à 48 kHz, aucune des 95 trames du fichier testé sans PNS. À 22,05 kHz sans PNS, 45/45 trames ; à 24 kHz sans PNS, 48/48 trames. Les résultats du mode `model` concernent uniquement les trames acceptées et ne doivent pas être comparés au fichier MP4 entier sans compter le squelette et les trames de repli.

## Adaptation et compatibilité

- Ajout de Mp4AudioEngine, Mp4Audio, Aac et AT ; réutilisation du mélangeur Ivo existant.
- Nouvel identifiant de moteur 31 (`MP4_AUDIO_RESEARCH`) et signature interne `M4A1`, afin de distinguer le nouveau flux du précédent M4V2.
- Paramètres de modèle figés ; aucune dépendance aux propriétés système tb/atb/lim/alr.
- Limite d'entrée 32 MiB, métadonnées et sortie bornées ; lectures exactes, Varints bornés, bornes des tables MP4 et validation des sections AAC. Contrôle du type mp4a et de la fréquence 22,05/24 kHz avant essai.
- Les trames/audio/slices non acceptées restent dans le squelette. Le routeur rejette tout candidat dont le fichier entier n'est pas exact.
- Aucun remplacement des classes Ivo, Mp3, Vp8, Mp4 historiques ; seules Router et EngineId ont été modifiées parmi les classes Java préexistantes.
- Le parcours de PliCm est diagonal ; il ne met pas en œuvre une courbe de Hilbert.

L'architecture multi-moteurs, le conteneur et la sélection par coût réel restent conservés. Les tables Huffman/CABAC, le lifting et les codeurs arithmétiques sont des composants de l'implémentation ; cet audit ne constitue pas une certification de nouveauté scientifique ou de propriété intellectuelle.

## Validation exécutée

`bash build.sh` passe sous Java 17. La suite inclut les contrôles généraux, 1 750 reconstructions DEFLATE, les contrôles JPEG, MP3, CABAC, les profils de mélangeur et les six scénarios FAST déjà présents.

Mp4AudioIntegrationTest vérifie les trois gains sur conteneurs complets, le déterminisme malgré des propriétés système parasites, la lecture ordinaire et compacte, les corruptions/troncatures, deux replis hors périmètre et la lecture exacte des neuf anciennes archives MP3/MP4/WebP. Les cinq contrôles indépendants IntegratedCheck passent également.

## Utiliser

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli compress-enhanced original.mp4 archive.ivo
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress archive.ivo restitue.mp4
```

Le moteur supplémentaire est sélectionné automatiquement en ENHANCED seulement s'il produit le plus petit conteneur exact. Les archives moteur 31 demandent cette nouvelle version du décompresseur ; cette version continue de lire les anciennes archives. La suite est reproductible avec les petites fixtures incluses.

## Traçabilité

SHA-256 du ZIP fourni : `c90e43c2e6e9e706f8436764608ba17e3420d1b559c51df3b8a297fe8863afa4`.
Les résultats bruts, les sources fournies, les empreintes et le journal de construction sont conservés dans research-results/2026-10-06/codec-audit. Les premières estimations `candidate_container_size` du banc Compare employaient un en-tête à payload vide, donc sous-estimaient de 1–2 octets le coût du Varint de longueur ; seules les mesures finales du routeur/pack des tests intégrés sont utilisées pour les gains retenus.
