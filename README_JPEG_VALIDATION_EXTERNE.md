# Validation JPEG sur un corpus externe — version 18

Un corpus externe est un ensemble de fichiers provenant d'autres sources et qui n'a pas servi à régler les modèles. Cette étape mesure la généralisation des gains de v17 ; elle n'introduit aucun nouveau modèle de compression.

## Protocole figé

26 JPEG originaux proviennent de cinq dépôts : libjxl/testdata, libjpeg-turbo/libjpeg-turbo, python-pillow/Pillow, pytorch/vision et pytorch/hub. Les fichiers sont téléchargés depuis des commits précis ; leurs URLs, tailles, empreintes SHA-256, dimensions, précisions et nombres de scans sont consignés dans le manifeste. Les sources n'ont pas été réencodées.

Un fichier sélectionné de Pillow, `flower.jpg`, était identique au `Canon_PowerShot_S40.jpg` du corpus précédent : il a été exclu avant mesure. Aucun des 26 SHA-256 retenus ne figure dans l'ancien corpus, et aucun doublon exact n'est retenu dans ce corpus externe.

Les six fichiers source des modèles de v17 sont comparés octet pour octet à ceux du paquet v17 déjà livré : tous sont identiques. Aucune catégorie, probabilité initiale, constante de projection, mixture ou option n'est ajustée après observation des résultats. L'adaptation des compteurs pendant un fichier reste le fonctionnement normal du codec ; elle est réinitialisée par composante.

Pour chaque JPEG compatible, la référence v16 est recalculée au coût IVO complet, puis les quatre variantes v17 (7 à 10) sont essayées. Le portefeuille conserve le plus petit résultat. Les JPEG refusés par le parseur spécialisé restent sur leur meilleure archive historique générique/IR/STORE, également vérifiée exactement.

## Résultats

Les **19 JPEG compatibles s'améliorent tous** : leur volume cumulé passe de **4 119 506 à 4 024 783 octets**, soit **94 723 octets économisés (2,30 % supplémentaires par rapport à v16)**. Cela confirme, sur ces fichiers externes, un gain du même ordre que les 2,31 % observés sur le corpus précédent.

Les sept autres fichiers conservent leur archive de repli, sans gain attribué aux nouveaux modèles. Sur l'ensemble des 26 fichiers, y compris ces cas hors support, le volume passe de **6 225 408 à 6 130 685 octets**, soit **1,52 %** de réduction supplémentaire. Les tailles sont celles des conteneurs complets avec les mêmes noms originaux.

| Dépôt | Fichiers | Compatibles | Gain en octets |
|---|---:|---:|---:|
| libjpeg-turbo/libjpeg-turbo | 4 | 2 | 338 |
| libjxl/testdata | 8 | 6 | 58910 |
| python-pillow/Pillow | 7 | 7 | 19709 |
| pytorch/hub | 1 | 1 | 10977 |
| pytorch/vision | 6 | 3 | 4789 |

| JPEG compatible | Meilleur v16 | Retenu v17 | Gain | Mode |
|---|---:|---:|---:|---:|
| libjxl_flower.png.im_q85_420.jpg | 464604 | 448739 | 15865 | 9 |
| libjxl_flower.png.im_q85_444.jpg | 578755 | 555644 | 23111 | 9 |
| libjxl_flower.png.im_q85_gray.jpg | 390222 | 377572 | 12650 | 9 |
| libjxl_flower_small.cmyk.jpg | 132003 | 127368 | 4635 | 9 |
| libjxl_bicycles_restarts.jpg | 79602 | 77314 | 2288 | 9 |
| libjxl_sideways_bench.jpg | 15320 | 14959 | 361 | 9 |
| libjpeg-turbo_testimgint.jpg | 5827 | 5656 | 171 | 9 |
| libjpeg-turbo_testorig.jpg | 5839 | 5672 | 167 | 9 |
| python-pillow_flower2.jpg | 73024 | 71482 | 1542 | 9 |
| python-pillow_hopper.jpg | 6477 | 6199 | 278 | 9 |
| python-pillow_hopper_gray.jpg | 5313 | 5118 | 195 | 9 |
| python-pillow_pil_sample_cmyk.jpg | 16352 | 15980 | 372 | 9 |
| python-pillow_pil_sample_rgb.jpg | 17325 | 17008 | 317 | 9 |
| python-pillow_rgb.jpg | 14958 | 14531 | 427 | 9 |
| python-pillow_xmp_test.jpg | 1561004 | 1544426 | 16578 | 9 |
| pytorch_astronaut.jpg | 37465 | 35944 | 1521 | 9 |
| pytorch_person1.jpg | 64428 | 62507 | 1921 | 9 |
| pytorch_pottery.jpg | 78819 | 77472 | 1347 | 9 |
| pytorch_dog.jpg | 572169 | 561192 | 10977 | 9 |

## Cas hors support spécialisé

| JPEG | Refus explicite du parseur |
|---|---|
| libjxl_flower.png.im_q85_420_progr.jpg | Progressive JPEG excluded from V0/V1 |
| libjxl_flower_small.q85_420_non_interleaved.jpg | V0/V1 expects one interleaved scan |
| libjpeg-turbo_monkey12.jpg | SOF0 missing |
| libjpeg-turbo_testimgari.jpg | SOF0 missing |
| pytorch_dog1.jpg | Progressive JPEG excluded from V0/V1 |
| pytorch_dog2.jpg | Progressive JPEG excluded from V0/V1 |
| pytorch_leaning_tower.jpg | Progressive JPEG excluded from V0/V1 |

Ces refus sont inclus dans le bilan. Les profils du manifeste permettent notamment de distinguer les JPEG progressifs, le fichier 12 bits, le fichier arithmétique et le fichier non entrelacé. Ils sont tous reconstruits à l'identique par le moteur de repli. La compatibilité CMYK est confirmée sur les exemples acceptés à quatre composantes ; cela ne garantit pas le support de tous les CMYK possibles.

## Exactitude et limites de l'expérience

**102 archives** sont décompressées par le CLI dans de nouveaux processus Java et comparées octet pour octet ainsi que par SHA-256 : 76 nouveaux candidats, 19 références v16 et sept archives de repli. Les archives finalement retenues sont identiques à une archive déjà vérifiée. Le build complet Java 17 et les tests existants passent.

Le corpus n'est pas un tirage aléatoire de toutes les images. Les trois grandes variantes de fleur et la petite version CMYK de libjxl sont corrélées ; les couples couleur/niveaux de gris ou RGB/CMYK de certains jeux de tests le sont aussi. Les deux fichiers testorig/testimgint de libjpeg sont des variantes de test. On ne doit pas traiter ces 26 fichiers comme 26 scènes indépendantes ni extrapoler 2,30 % à tous les JPEG.

Un contrôle descriptif séparé sur les quatre photos compatibles du groupe PyTorch (astronaut, person1, pottery et dog) donne également quatre gains : **15 766 octets**, soit environ **2,09 %** de leur volume v16. Les trois autres photos de ce groupe sont progressives et conservent le repli. Ce contrôle limite la dépendance du résultat aux seules variantes de fleur, sans constituer une estimation statistique universelle.

Mesures complètes, profils, provenance, empreintes, témoins et logs : `research-results/2026-10-04/jpeg-external`. Les temps incluent les reconstructions et plusieurs encodages et ne constituent pas un benchmark à froid. Les images publiques ne sont pas redistribuées dans le paquet ; elles restent récupérables par les URLs figées du manifeste.

## Reproduire un JPEG

Java 17, depuis la racine :

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.jpeg.JpegExternalMeasure original.jpg resultats
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress resultats/retained-best.ivo restaure.jpg
```

Le programme écrit la référence v16 et les quatre modèles de v17 lorsqu'ils sont compatibles. Pour un JPEG refusé, il écrit son repli exact et un statut UNSUPPORTED. Dans les deux cas, `retained-best.ivo` est l'archive retenue par cette recherche. Le recalcul complet de la référence peut demander plusieurs encodages.

Cette version ajoute le programme de validation et les résultats. La compression automatique du routeur général n'est pas modifiée ; les moteurs expérimentaux restent accessibles par les commandes de recherche, avec décompression intégrée. Le paquet distribué ne contient aucun `.jar`, `.py` ni `.class` ; le build crée ses fichiers compilés localement.

## Conclusion de recherche

La validation externe est positive pour les JPEG baseline acceptés. Elle rend raisonnable l'intégration du meilleur modèle physique dans un portefeuille avec comparaison au coût final et repli exact. Cette promotion n'est pas effectuée dans cette version de validation. L'extension aux progressifs et multiscans reste une recherche distincte, indispensable pour élargir la couverture spécialisée.
