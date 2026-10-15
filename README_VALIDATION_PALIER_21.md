# Validation externe des modèles de version 20 — version 21

Les modèles et codeurs sont identiques à ceux de la version 20 : leurs empreintes sont comparées à l’archive source précédente. Les nouvelles sources sont figées par URL, commit lorsqu’il existe, et SHA-256. Aucun paramètre n’est ajusté sur ce corpus et aucune source n’est un doublon exact de celles des rapports précédents.

## JPEG

Douze nouveaux fichiers : quatre images progressives de taille significative, quatre petits cas de conformité pris en charge, un fichier refusé par le lecteur strict, et trois nouveaux multiscans de la scène flower déjà utilisée. Ces trois derniers valident des structures supplémentaires, sans compter comme trois nouvelles scènes.

| Image progressive | Ancien meilleur IVO | JPS1 retenu | Gain |
| --- | ---: | ---: | ---: |
| image-rs tower | 68 090 | 63 812 | 4 278 |
| image-rs progressive3 | 91 050 | 90 020 | 1 030 |
| is-progressive kitten | 83 831 | 81 702 | 2 129 |
| is-progressive progressive | 24 416 | 24 327 | 89 |
| Total de ces quatre images | 267 387 | 259 861 | 7 526 |

Le gain supplémentaire est **2,815 %** des archives précédentes. Les quatre gagnent, avec le même portefeuille JPS1 modes 0/1. Les quatre petits cas gardent les anciens moteurs, dont les en-têtes et modèles coûtent moins cher. Les trois multiscans flower gagnent 10 136 octets au total ; ils couvrent maintenant deux scans partiellement entrelacés et trois scans non entrelacés, en 4:2:0 et 4:4:4.

`partial_progressive.jpg` contient notamment FE DA à l’emplacement attendu d’un marqueur. Le parseur strict refuse ce fichier ; son archive de référence générale est décompressée exactement. Aucun contournement, correction des octets originaux ou modification du modèle n’est réalisé pour le faire entrer dans le corpus compatible.

Les 22 candidats des onze fichiers compatibles et les douze références ont été décompressés par des processus CLI séparés et comparés par SHA-256. Les onze fichiers compatibles passent aussi l’oracle ImageIO indépendant : pixels du JPEG source comparés aux pixels des coefficients Java réémis dans un JPEG séquentiel simple. Les scans incomplets sans DC ou sans AC sont conservés exactement lorsqu’ils sont pris en charge.

## MP3

Dix nouvelles sources : deux clips de parole (chapitre d’Alice et exemple Brooklyn Bridge), deux exemples musicaux, cinq pistes instrumentales liées à la même composition, et une ambiance de foule. Les cinq pistes ne constituent pas cinq productions musicales indépendantes. Les fichiers restent ceux publiés : aucun découpage ou transcodage.

La référence inclut STORE, N&C, REF+N&C, MP3 V1, les quatre schémas MP39, les huit MP40 et les trois MP41. Tous les coûts sont ceux d’archives IVO complètes, avec les mêmes nom, longueur et empreinte. EnergyExternalMeasure construit cette référence puis teste s15/s16 et les contrôles s17/s18, avec les modèles figés. Il vérifie aussi la reconstruction exacte et les statistiques spectrales de chaque schéma historique.

**Sept fichiers sur dix gagnent avec s15/s16**, et les trois autres conservent le meilleur précédent. Le portefeuille étendu économise **21 878 octets** au total : 10 396 102 → 10 374 224, soit **0,210 %** supplémentaires sur ces fichiers.

| Source | Meilleur précédent IVO | Meilleur contexte d’énergie | Gain retenu |
| --- | ---: | ---: | ---: |
| Brooklyn Bridge, parole courte | 26 313 | 27 511 (s15) | 0 |
| Alice, chapitre de parole | 4 971 610 | 4 961 589 (s15) | 10 021 |
| Bassguitar | 472 775 | 525 522 (s15) | 0 |
| Clav | 583 476 | 594 308 (s16) | 0 |
| Concert crowd | 183 185 | 183 023 (s15) | 162 |
| Drums | 607 073 | 606 639 (s15) | 434 |
| Horns | 564 364 | 563 812 (s15) | 552 |
| Leadguitar | 585 627 | 584 630 (s16) | 997 |
| Outfoxing | 1 766 982 | 1 760 846 (s16) | 6 136 |
| Viper | 634 697 | 631 121 (s15) | 3 576 |

Le gain de 10 021 octets sur Alice représente 0,202 % de sa référence ; Outfoxing gagne 6 136 octets (0,347 %) et Viper 3 576 (0,563 %). Les contrôles s17/s18 ne battent pas le meilleur contexte d’énergie sur ces trois fichiers.

La concurrence avec les anciens moteurs est essentielle : forcer s15/s16 sur les dix fichiers donnerait **10 439 001 octets**, soit **42 899 octets de plus** que le portefeuille précédent. Le taux de réussite 7/10 n’implique donc pas une amélioration moyenne sans sélection. Bassguitar et Clav illustrent cette limite. Les cinq pistes liées à une même composition réduisent aussi l’indépendance du corpus ; ce résultat ne fournit pas un gain moyen universel pour la musique ou la parole.

Les **40 candidats MP42** et les **dix références** sont décompressés dans 50 nouveaux processus CLI, avec SHA-256 identique au source. Les 150 variantes MP39/40/41 ont aussi leurs octets et statistiques spectrales vérifiés au cours des mesures. Les données nouvelles sont stéréo, à 24 000, 32 000 et 44 100 Hz ; elles ne constituent pas une nouvelle validation exhaustive de toutes les configurations MPEG.


## Reproduire

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.ProgressiveScanMeasure essais-jpeg image.jpg
java -Xmx1g -cp target/classes:target/test-classes io.ivo.jpeg.ProgressiveScanCodecTest image.jpg
java -Xmx2g -cp target/classes io.ivo.mp3.EnergyExternalMeasure audio.mp3 essais-mp3
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress essais-mp3/energy-s15.ivo restaure.mp3
```

Pour JPEG, lancer le programme de mesure fichier par fichier : la référence est écrite avant tout refus du codec expérimental. Les programmes Java nomment les arguments dans leurs variables. Les sources figées, mesures, empreintes, logs, petites archives et validations figurent dans `research-results/2026-10-04/validation-v21`. Les grandes sources/archives audio sont omises du ZIP ; les URL et SHA-256 permettent de les retrouver.

Le portefeuille automatique reste celui de version 19 ; les nouveaux codecs sont disponibles pour essais et lecture, sans promotion automatique dans cette validation. Aucun JAR, script Python ou fichier class dans le livrable.

## Décision

Le JPEG confirme un gain sur les quatre nouvelles images, avec onze lecteurs/réémetteurs vérifiés par un oracle indépendant et une exclusion documentée. L’énergie MP3 gagne aussi sur de la parole et des exemples musicaux nouveaux, mais doit rester en concurrence avec les autres schémas. Cette version ajoute un programme de validation reproductible et des rapports ; elle ne change aucun modèle ni le routage automatique.

Au total : **62 nouveaux candidats exacts**, **22 références exactes**, donc **84 décompressions CLI dans des processus distincts**, plus les contrôles internes et pixels JPEG. Les tests généraux passent. Le MP4 n’est pas remesuré dans ce palier : le gain faible de version 20 n’est pas assimilé à une validation plus large.
