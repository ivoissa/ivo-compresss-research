# Revue et intégration du code utilisateur — version 24

Le code apporte un progrès mesuré, surtout sur le MP4. Le mélange adaptatif de contextes, le codage arithmétique entier et les lecteurs de symboles propres à chaque format permettent de travailler sur les données compressées elles-mêmes. Le lecteur/encodeur CABAC MP4 est nettement plus avancé que notre recherche précédente limitée aux préfixes.

## Résultats

Les tailles suivantes incluent le conteneur IVO complet, son identifiant de moteur et son contrôle SHA-256. Chaque archive a été décodée dans un nouveau processus et comparée octet par octet, y compris avec des propriétés système différentes.

| Source | Format | Original (octets) | IVO (octets) | Gain |
|---|---|---:|---:|---:|
| tao-10s | MP3 | 357,362 | 320,146 | 10.414 % |
| synthetic-mono | MP3 | 33,062 | 22,667 | 31.441 % |
| synthetic-stereo | MP3 | 33,062 | 23,387 | 29.263 % |
| test | WEBP | 3,932 | 3,656 | 7.019 % |
| kitten | WEBP | 51,064 | 47,875 | 6.245 % |
| tower | WEBP | 24,970 | 23,964 | 4.029 % |
| poc0 | MP4 | 27,245 | 26,716 | 1.942 % |
| terminal | MP4 | 2,015 | 1,429 | 29.082 % |
| transform8 | MP4 | 6,553 | 5,861 | 10.560 % |
| whatsapp | MP4 | 5,427,643 | 4,777,717 | 11.974 % |

Sur WhatsApp, notre meilleure référence précédente était de 5 414 803 octets : le nouveau moteur gagne encore 637 086 octets. Les 709 slices sont modélisées, sans recours au stockage brut des slices. Sur le même extrait MP3 de dix secondes, les 19 anciens candidats ont été remesurés : le meilleur faisait 338 684 octets, contre 320 146 ici, soit 18 538 octets supplémentaires gagnés.

Ces chiffres ne constituent pas une moyenne générale. Le gros résultat MP4 concerne une seule vidéo utilisateur. Le MP3 est un extrait copié sans réencodage, avec sa couverture ID3 conservée, et non une mesure du fichier Tao complet. Les trois WebP sont des entrées VP8 générées pour ces essais : la recompression restitue exactement ces WebP, sans prétendre que la conversion initiale JPEG vers WebP était sans perte. Les fichiers synthétiques ne remplacent pas un corpus naturel indépendant.

## Adaptations et intégration

Les sept fichiers reçus sont conservés inchangés sous `research-experiments/user-code/original`. Le manifeste contient leurs empreintes et celles des copies adaptées sous `src/main/java/io/ivo/usercode`.

Trois adaptations MP4 permettent le résultat WhatsApp : prise en charge de POC type 2, suppression d'un octet 03 terminal ajouté systématiquement par l'échappement, et conservation d'un suffixe original lorsque la représentation EBSP reconstruite diffère. Le préfixe CABAC reconstruit est contrôlé avant acceptation. Le format interne devient M4V2, car les métadonnées du suffixe changent. Le patch correspondant est conservé dans `research-experiments/user-code/mp4-adaptation.patch`.

Les modèles gardent les paramètres par défaut du code reçu. Ces valeurs sont figées dans les copies intégrées : une propriété système ne doit pas changer silencieusement le modèle nécessaire à une archive existante. Les moteurs expérimentaux ont des identifiants distincts (28 MP3, 29 MP4, 30 WebP). Le routeur sait décoder leurs archives mais ne les sélectionne pas automatiquement. Le programme d'essai exige une restitution complète exacte avant d'écrire une archive, contrairement à un simple affichage `identical=false`.

Le code reçu reste un prototype : lectures et allocations issues des métadonnées à borner, gestion des fins de fichier à renforcer, consommation mémoire importante et état global/console à revoir pour des appels concurrents. Le MP4 spécialisé est limité à certains flux CABAC progressifs I/P 8 bits 4:2:0 ; les slices B, P pondérées, matrices personnalisées et conteneurs fragmentés nécessitent encore du travail. Le MP3 spécialisé vise MPEG-1 Layer III. Un gain sur un fichier non modélisé peut provenir du chemin générique DEFLATE : le petit essai POC0 initial gagnait 2,14 % sans aucune slice modélisée.

## Reproduire

Depuis la racine du projet, avec Java 17 ou supérieur :

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.usercode.UserCodecTrial MP4 original.mp4 output.ivo
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress output.ivo restored.mp4
cmp original.mp4 restored.mp4
```

Remplacer MP4 par MP3 ou WEBP selon la source. Les arguments du nouveau main sont affectés à des variables nommées. Les journaux, mesures, empreintes et petits cas reproductibles sont sous `research-results/2026-10-05/user-code-review`. La construction générale a réussi et dix nouvelles archives ont été vérifiées dans des processus distincts. Un contrôle du routeur automatique conserve le moteur antérieur MP4_V3.

La prochaine validation utile est un corpus naturel indépendant, avec comparaison de la taille finale, du temps et de la mémoire, puis renforcement des lecteurs avant une éventuelle sélection automatique.
