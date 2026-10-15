# Compression par défaut : sélection AUTO — 8 octobre 2026

`compress` et `new Router()` utilisent désormais `AUTO`. Pour un JPEG classique de plus de 16 Kio, la sélection compare STORE, JPEG_IR et les profils de recherche 15/16, puis retient le plus petit conteneur dont la reconstruction est exacte. L'ancien portefeuille JPEG V6.1 et les codeurs génériques N&C/REF ne sont plus exécutés sur ce chemin. Tous les décodeurs et identifiants existants sont conservés.

Pour un JPEG progressif ou multiscan de plus de 16 Kio, AUTO compare STORE, JPEG_IR et le portefeuille scan 0/4. Les petits JPEG gardent le portefeuille exhaustif, ce qui préserve les anciens gagnants comme le profil 14 sur les petites fixtures. Un JPEG malformé est conservé exactement avec STORE. Pour les autres formats, AUTO reprend le chemin FAST existant, avec comparaison supplémentaire MP4/AAC lorsque le prototype 22,05/24 kHz est admissible. Il ne s'agit pas d'une nouvelle optimisation de tous les autres formats.

Le classement est fondé sur les profils validés dans notre recherche. AUTO ne garantit pas le minimum parmi toutes les expériences sur chaque nouveau fichier. Le mode exhaustif reste disponible pour cette comparaison plus large. Aucun délai maximal de compression n'est garanti.

## Mesure sur la photo jointe

Copie testée : JPEG classique RGB 1 536 × 1 152, 597 558 octets, un scan, sans EXIF dans cette copie. SHA-256 : `15476ee34ae786f94bac6477bb0a86836d3ed779c1b2b2eebc45adb35c62b7a7`.

Même nom conservé dans le conteneur pour toutes les comparaisons : `27667.jpg`.

| Sélection | Durée de compression vérifiée | Taille IVO complète | Profil gagnant |
|---|---:|---:|---|
| STANDARD, ancien `compress` | 17,067 s | 519 596 octets | 9 |
| FAST, ancien `compress-fast` | 19,971 s | 505 582 octets | 15 |
| ENHANCED, portefeuille exhaustif | 38,351 s | 505 582 octets | 15 |
| AUTO, nouveau `compress`, première exécution | 7,473 s | 505 582 octets | 15 |
| AUTO, médiane des trois répétitions suivantes | 6,856 s | 505 582 octets | 15 |

AUTO, FAST et ENHANCED produisent des archives identiques octet par octet sur ce fichier. La réduction par rapport au JPEG original est de 15,39 %, et la réduction supplémentaire face à STANDARD est de 2,70 %. La décompression AUTO prend environ 1,1–1,2 s dans ces mesures.

Une exécution CLI séparée avec `-Xms64m -Xmx512m` réussit en 7,185 s, démarrage JVM et écriture compris. RSS maximal du processus : 259 384 Kio, soit 253,3 Mio. Ce RSS comprend davantage que le heap Java et ne décrit pas une borne pour les photos plus grandes. Le CLI a conservé le nom local `01-27667.jpg`, donc son archive mesure 505 585 octets : trois octets de nom supplémentaires, payload identique.

Environnement : Linux, OpenJDK 17.0.20, CPU serveur AMD EPYC 9V74. Les essais de comparaison emploient `-Xms64m -Xmx2g`. Les temps de compression en mémoire comprennent le codage, les vérifications exactes des candidats et le conteneur ; ils excluent la lecture et l'écriture des fichiers. STANDARD/FAST sont mesurés une fois dans une même JVM ; AUTO une fois à froid puis trois répétitions dans une autre JVM ; ENHANCED suit ces répétitions. L'ordre n'est pas randomisé et les JVM/environnement partagé peuvent introduire de la variabilité : ces observations établissent un ordre de grandeur et un gain sur ce fichier, pas une garantie de débit universelle.

La copie jointe compte 1,77 mégapixel. Un original Fold5 de résolution supérieure ou un ordinateur différent demande sa propre mesure. La durée ne se déduit pas uniquement du poids JPEG : le nombre de blocs, les détails, les métadonnées, le CPU et la mémoire interviennent. Les boucles de codage restent principalement séquentielles et la sélection parse encore le JPEG pour plusieurs profils ; il existe donc d'autres pistes d'optimisation.

## Utilisation

```sh
bash build.sh
java -Xms64m -Xmx512m -cp target/classes io.ivo.cli.IvoCli compress 27667.jpg photo.ivo
java -cp target/classes io.ivo.cli.IvoCli decompress photo.ivo restitue.jpg
```

Modes explicites :

- `compress-standard` / `bench-standard` : ancien portefeuille STANDARD.
- `compress-enhanced` / `bench-enhanced` : essais exhaustifs.
- `compress-fast` et `compress-compact` : modes explicites existants, conservés.
- `compress` / `bench` : sélection AUTO par défaut.

Une photo plus grande peut nécessiter un heap supérieur à celui validé sur cette copie. Le paramètre `-Xmx` est une limite mémoire, pas une accélération ni un délai maximal.

## Vérifications

Les archives AUTO ont été comparées exactement à FAST/ENHANCED sur cette photo. Une JVM contenant les classes avant cette modification a également restauré l'archive AUTO octet par octet. AutoRouterTest couvre le fichier réel, le nombre réduit de moteurs exécutés, l'ancien gagnant des petits JPEG, le progressif, un JPEG malformé, le JSON inchangé face à FAST et la lecture par STANDARD. La suite générale est lancée sur le dossier effectivement empaqueté ; les anciens tests de portefeuille historique utilisent désormais explicitement STANDARD lorsque c'est ce comportement qu'ils vérifient.

Les résultats et journaux sont dans `research-results/2026-10-08/jpeg-auto/`.

## Paquet reconstruit

La copie précédemment enregistrée du ZIP était tronquée et illisible. Ce paquet est reconstruit depuis le dossier complet de sources qui avait servi aux validations du 6 octobre. Il conserve toutes les classes Java, les tests, les documents, les rapports textuels et les petites fixtures nécessaires à la suite. Les sorties binaires historiques volumineuses des expériences ne sont pas incluses ; les sources et les décodeurs correspondants restent présents. Le fichier ZIP est vérifié avant livraison et sa copie enregistrée est contrôlée après transfert.
