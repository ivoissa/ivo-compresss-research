# IVO Java 1.5 — runtime 0.7 et ZIP Bayes exact

Cette livraison Java 17 regroupe le conteneur IVO exécutable et les derniers
modèles de recherche JPEG, MP3, MP4, PDF V2 et ZIP/DOCX V2.

## Règle d'exactitude

Un moteur n'est éligible que si son décodage reproduit chaque octet du fichier
original. Le runtime IVO ajoute en plus la taille originale et son SHA-256.

## Construction

```bash
./build.sh
```

Le script fonctionne avec les exécutables `javac`/`jar` ou directement avec
les modules du JDK lorsque ces exécutables ne sont pas exposés dans le PATH.

Fichiers produits :

- `target/ivo-0.7.0-SNAPSHOT.jar` : runtime IVO1 ;
- `target/ivo-research-1.5.0.jar` : modèles spécialisés et benchmarks exacts.

## Runtime IVO

```bash
java -jar target/ivo-0.7.0-SNAPSHOT.jar compress fichier.bin fichier.ivo
java -jar target/ivo-0.7.0-SNAPSHOT.jar decompress fichier.ivo fichier.restaure
java -jar target/ivo-0.7.0-SNAPSHOT.jar bench fichier.bin
```

Le routeur sélectionne PDF V2 pour les PDF compatibles et compare désormais
ZIP/DOCX V1, V3 et ZIP Bayes V1 sur les `.docx`. ZIP Bayes est également essayé
sur les autres conteneurs ZIP. Le plus petit conteneur exact gagne.

## Modèles spécialisés

```bash
# JPEG V6.1 : coefficients + reconstruction exacte du scan original
java -jar target/ivo-research-1.5.0.jar jpeg-v61 image.jpg

# MP3 : structure, cordes, réservoir, N&C et REF
java -jar target/ivo-research-1.5.0.jar mp3 fichier.mp3
java -jar target/ivo-research-1.5.0.jar mp3-ref fichier.mp3
java -jar target/ivo-research-1.5.0.jar mp3-side fichier.mp3

# MP4 : boîtes, tables classiques, fragments tfhd/trun/trex et syntaxe NAL
java -jar target/ivo-research-1.5.0.jar mp4 fichier.mp4

# PDF V1 : recherche binaire des JPEG
java -jar target/ivo-research-1.5.0.jar pdf-image fichier.pdf

# PDF V2 retenu : flux isolés, JPEG V6.1 transversaux, JPX/Flate regroupés
java -jar target/ivo-research-1.5.0.jar pdf-v2 fichier.pdf

# Profil DEFLATE-IR de recherche (exact, mais non retenu sur le corpus)
java -jar target/ivo-research-1.5.0.jar pdf-v2 --deflate-ir fichier.pdf

# ZIP/DOCX : entrées, données DEFLATE et structure ZIP exacte
java -jar target/ivo-research-1.5.0.jar zip-docx fichier.docx

# DOCX V2 expérimental : cinq cordes DEFLATE agrégées
java -jar target/ivo-research-1.5.0.jar zip-docx-v2 fichier.docx

# DOCX V3 retenu : champs ZIP factorisés, flux compressés groupés bruts
java -jar target/ivo-research-1.5.0.jar zip-docx-v3 fichier.docx

# Diagnostic direct du moteur désormais intégré au routeur global
java -Xmx2g -jar target/ivo-research-1.5.0.jar zip-docx-bayes fichier.docx

# Tests du noyau
java -jar target/ivo-research-1.5.0.jar selftest
```

## État des moteurs

| Modèle | Transformation exacte | Archive finale gelée |
|---|---:|---:|
| STORE / N&C / REF+N&C | oui | oui, IVO1 expérimental |
| DEFLATE IR | oui | oui, flux isolés |
| JPEG IR | oui | oui |
| JPEG V6.1 | oui | moteur runtime 9 |
| MP3 structure/REF sémantique | oui | moteur runtime 10 |
| MP4 classique/fragmenté/NAL | oui | moteurs runtime 11/12/13 |
| PDF-IMAGE V1 | oui | remplacé par PDF V2 |
| PDF V2 GROUPED_RAW | oui | moteur runtime 6 |
| PDF V2 DEFLATE_IR | oui | expérimental, moins bon sur le gros PDF |
| ZIP/DOCX V1 | oui | moteur runtime 7 |
| ZIP/DOCX V2 | oui | expérimental, rejeté sur le DOCX testé |
| ZIP/DOCX V3 | oui | moteur runtime 8 |
| ZIP Bayes V1 | oui | moteur runtime 14 |
| ZIP/DOCX V3 | oui | moteur runtime 8, profil retenu |

Les modèles de recherche restent disponibles pour les balayages, mais JPEG,
MP3 et MP4 produisent désormais de vrais payloads autodécodables dans le
runtime. Le routeur compare leur conteneur IVO final et vérifie chaque round-trip.

## Résultats réels de validation

| Fichier | Original | IVO candidat | Gain | Exact |
|---|---:|---:|---:|---:|
| JPEG réel | 342 350 | 319 689 | 6,62 % | oui |
| MP3 réel 1 | 2 635 038 | 2 368 461 | 10,12 % | oui |
| MP3 réel 2 | 6 385 532 | 3 255 006 | 49,03 % | oui |
| MP4 réel — V3 | 4 244 346 | 4 196 431 | 1,13 % | oui |
| PDF médical — runtime V2 | 1 073 232 | 847 728 | 21,01 % | oui |
| PDF livre scanné — runtime V2 GROUPED_RAW | 32 186 791 | 28 497 492 | 11,46 % | oui |
| PDF livre scanné — V2 DEFLATE_IR | 32 186 791 | 28 704 096 | 10,82 % | oui |
| DOCX — runtime V3 | 245 394 | 242 431 | 1,21 % | oui |
| DOCX — V2 DEFLATE_IR | 245 394 | 290 104 | -18,22 % | oui |

## Limites connues

- PDF V2 reconnaît les objets `stream` et les filtres directs du corpus. Pour
  les `/Length` indirects, il utilise une recherche prudente de `endstream` :
  ce n'est pas encore un parseur PDF général couvrant toute la spécification.
- Les flux JPX sont isolés et agrégés sans transformation sémantique.
- ZIP/DOCX V1 refuse ZIP64 ; les entrées classiques avec ou sans data
  descriptor restent exactes grâce aux tailles du répertoire central.
- ZIP/DOCX V3 cible les archives classiques dont les champs locaux et centraux
  peuvent être factorisés exactement ; le routeur revient automatiquement à
  V1 dans les autres cas.
- MP4 V3 suppose des échantillons vidéo entièrement compatibles avec le
  framing NAL sur 4 octets ; V2 et V1 assurent le repli exact.
- Le profil maximal privilégie le ratio et peut être lent sur de gros PDF.
