# Mélange de contextes JPEG — version 26

Le transfert du prédicteur adaptatif du code utilisateur donne un gain supplémentaire mesuré sur six JPEG naturels : **23 436 octets, soit 2,036 % par rapport au portefeuille automatique de version 25**, conteneurs IVO complets compris. Les modèles sont figés et les sorties restituées exactement. Le programme `compress-enhanced` compare maintenant ces candidats aux moteurs habituels.

## Ce qui change

Le lecteur JPEG, la reconstruction des coefficients, les tables Huffman originales, les marqueurs, les bits de bourrage et le patch d'entropie exact restent ceux du moteur existant. On réutilise `Ivo.CM` et son codeur arithmétique entier pour modifier seulement deux cordes :

- présence d'un coefficient AC, avec huit projections de contexte, tables lentes/rapides, poids adaptatifs et calibration APM ;
- catégorie d'amplitude AC, transformée en décisions binaires de seuil avec dix projections causales (bande, voisins, fréquence précédente, bloc similaire et frontière).

Les signes, amplitudes détaillées, DC et métadonnées ne changent pas. Aucun paramètre de modèle n'est transmis par une propriété système ; chaque profil possède des constantes figées. Les tables hachées sont limitées à 16 bits dans cet essai, au lieu de reprendre les grandes tables MP3/MP4.

| Modes | Présence | Catégories | Différence du mode pair |
|---|---|---|---|
| 9 / 10 | Mélange bayésien antérieur | Mélange bayésien antérieur | Prédiction physique retirée du contexte de présence |
| 11 / 12 | Nouveau CM | Mélange bayésien antérieur | Même contrôle sur la présence |
| 13 / 14 | Mélange bayésien antérieur | Nouveau CM avec décisions de seuil | Même contrôle sur la présence |
| 15 / 16 | Nouveau CM | Nouveau CM avec décisions de seuil | Même contrôle sur la présence |

Ces numéros sont stockés dans le profil du payload de coefficients. Les anciens profils restent décodables ; les anciens logiciels ne connaissant pas les nouveaux profils doivent être mis à jour pour lire leurs archives.

## Mesures sur les images naturelles

| Source | Référence IVO (octets) | Nouveau IVO (octets) | Gain supplémentaire | Profil |
|---|---:|---:|---:|---:|
| user-25060 | 169,080 | 166,708 | 2,372 | 15 |
| user-25066 | 93,714 | 92,560 | 1,154 | 16 |
| user-19414 | 165,913 | 160,911 | 5,002 | 15 |
| user-22626 | 508,989 | 497,727 | 11,262 | 15 |
| user-24792 | 116,393 | 114,476 | 1,917 | 15 |
| holdout-24790 | 97,200 | 95,471 | 1,729 | 15 |
| Total | 1,151,289 | 1,127,853 | 23,436 | |

Les originaux totalisent 1,358,504 octets. Le gain global par rapport à ces originaux est de 16.978 %, contre 15.253 % avec le portefeuille précédent. Les **2,036 %** annoncés expriment la réduction supplémentaire de la taille IVO précédente.

La présence seule gagne déjà sur les cinq premières images, mais modestement. Les catégories apportent la plus grande part du progrès. La combinaison gagne sur les six images ; le profil 16 est meilleur que le 15 sur 25066. Les résultats sont ceux d'une sélection entre un nombre fini de profils dont le coût complet est compté, et non ceux d'un réglage individuel des paramètres.

La sixième image, 24790, a été ajoutée après le gel des profils : 97 200 vers 95 471 octets, soit 1 729 octets supplémentaires gagnés. C'est une vérification supplémentaire sur un fichier, pas une preuve d'indépendance des scènes ou une moyenne universelle.

## Validation et limites

- Six profils sur dix entrées prises en charge (quatre fixtures de développement et six images naturelles) : **60 archives** décodées dans de nouveaux processus, y compris avec des propriétés système différentes, puis comparées octet par octet.
- Tests sur 10 000 décisions de présence avec changements de régime et 10 000 catégories couvrant les valeurs 1 à 10 ; restitution exacte des profils 9 à 16 sur deux fixtures.
- Comparaison de portefeuille sur trois fixtures, dont un très petit fichier pour lequel STORE reste meilleur.
- Construction générale et tests JPEG progressifs, DEFLATE, MP3, MP4 et intégration utilisateur réussis.
- Un essai réel `compress-enhanced` sélectionne le profil 15 sur 24790 et restitue le fichier dans un autre processus avec SHA-256 et comparaison exacte.

Le présent essai porte sur les JPEG séquentiels baseline. Les deux images PyTorch récupérées sont progressives et ont été exclues explicitement ; elles ne contribuent pas au gain annoncé. Le lecteur progressif/multiscan reste une autre voie de recherche. Les résultats concernent ces fichiers, sans validation générale sur un nouveau corpus externe baseline. La référence est le portefeuille automatique existant ; toutes les anciennes variantes de recherche n'ont pas été remesurées sur ces six images.

Les premiers très petits cas montrent que le nouveau modèle peut être plus gros ; le routeur compare toujours avec les anciens moteurs et STORE. Ajouter les profils augmente le temps de compression : sur 24790, la ligne du moteur JPEG comparant les huit profils mesure environ 4,5 secondes dans cette exécution. Les timings détaillés des candidats incluent les contrôles de restitution et ne sont pas des mesures de débit pur.

## Utiliser et reproduire

```sh
bash build.sh
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli compress-enhanced image.jpg image.ivo
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress image.ivo restored.jpg
cmp image.jpg restored.jpg
```

Le portefeuille standard conserve les profils 9/10. Le portefeuille explicite `new Router(true)` ou `compress-enhanced` compare aussi les profils 11 à 16. Le décompresseur standard lit les deux.

Pour retrouver les six candidats isolés :

```sh
java -Xmx1g -cp target/classes io.ivo.jpeg.JpegMixerMeasure image.jpg resultats presence
java -Xmx1g -cp target/classes io.ivo.jpeg.JpegMixerMeasure image.jpg resultats category
```

Les mesures, empreintes, profils figés, journaux, sources d'essai et archives retenues sont sous `research-results/2026-10-05/jpeg-context-mixer`. Pour les images naturelles, le projet conserve la référence et l'archive gagnante ; les candidats intermédiaires sont reproductibles par ces commandes et leurs empreintes sont enregistrées. Les données originales reçues et leurs sept classes Java restent inchangées.

La suite la plus utile pour cette voie JPEG est d'étendre la validation à d'autres JPEG baseline naturels avant une promotion par défaut, puis d'étudier le mélange des décisions de raffinement progressif. Le prochain essai MP3 reste le codage par différences des facteurs d'échelle, avec un profil distinct.
