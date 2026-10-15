# JPEG progressifs : mélange adaptatif — version 27

Le test est positif sur le corpus de validation de six JPEG progressifs : **26 274 octets supplémentaires gagnés, soit 6,296 % de réduction de la taille IVO précédente**. La restitution des fichiers est exacte. Cinq fichiers gagnent ; le sixième, très petit, conserve son ancien résultat.

La référence de chaque fichier est le minimum entre le portefeuille automatique antérieur et les deux anciens profils progressifs JPS1 (modes 0/1). La comparaison ne se limite donc pas à STORE ou au seul portefeuille standard, qui ne sélectionnait pas encore les essais progressifs. Le même nom de fichier et le même conteneur IVO complet sont comptés des deux côtés.

## Résultats

| Fichier progressif | Meilleure référence IVO | Résultat retenu IVO | Gain supplémentaire | Profil retenu |
|---|---:|---:|---:|---|
| is-progressive_kitten-progressive.jpg | 81,702 | 76,496 | 5,206 | 4 |
| image-rs_tower_progressive.jpg | 63,812 | 59,542 | 4,270 | 4 |
| image-rs_progressive3.jpg | 90,020 | 84,911 | 5,109 | 4 |
| image-rs_jpg-progressive.jpg | 884 | 884 | 0 | reference |
| pytorch_dog1.jpg | 93,744 | 87,444 | 6,300 | 4 |
| pytorch_dog2.jpg | 87,150 | 81,761 | 5,389 | 4 |
| Total | 417,312 | 391,038 | 26,274 | |

Les originaux totalisent 432,263 octets. Le gain par rapport aux originaux passe de 3.459 % à 9.537 %. Les 6,296 % annoncés sont le gain supplémentaire sur les anciennes archives IVO.

Quatre fichiers progressifs dérivés ont servi de cas de développement, sans être ajoutés à ce total. Les deux cas séquentiels multiscans, dont un avec marqueurs de reprise, passent aussi exactement et gagnent respectivement 211 et 208 octets. Ils sont des fixtures de validation, pas un corpus naturel de multiscans.

## Modèles testés

| Profil | Format | Symboles Huffman | Bits de raffinement |
|---|---|---|---|
| 0 / 1 | JPS1 antérieur | Cordes génériques par phase, puis phase/bande | Cordes génériques |
| 2 | JPS2 | Cordes génériques antérieures | Nouveau mélange avec coefficients déjà connus |
| 3 | JPS2 | Cordes génériques antérieures | Contrôle sans les valeurs des coefficients et voisins |
| 4 | JPS2 | Nouveau mélange causal | Nouveau mélange causal |
| 5 | JPS2 | Contrôle sans les valeurs des coefficients et voisins | Même contrôle |

Les experts rapides/lents, poids adaptatifs, calibration et arithmétique entière viennent du `Ivo.CM` fourni par l'utilisateur. Les deux nouveaux modèles emploient huit projections et des tables de 16 bits, avec des paramètres figés.

Les contextes utilisent uniquement l'état disponible avant la décision : phase, fréquence, plan d'approximation, symbole précédent, coefficient déjà connu et voisins déjà reconstruits. Le tableau de coefficients démarre vide et se remplit pendant la lecture des scans ; aucun coefficient futur ou décodage préalable de l'image complète n'alimente le prédicteur.

Le raffinement seul apporte un gain modeste, par exemple 303 octets sur le chaton. La combinaison avec le mélange des symboles atteint 5 206 octets sur ce fichier. Le profil causal 4 est meilleur que le contrôle 5 sur les cinq fichiers progressifs améliorés, ce qui soutient l'intérêt des contextes de coefficients pour ce corpus.

La représentation garde les symboles Huffman originaux et reconstruit les mêmes codes, amplitudes, corrections, scans, marqueurs, bourrages et octets de traîne. Il n'y a pas de conversion d'image ni de réencodage avec perte. Les flux mélangés utilisent les deux cordes auparavant réservées, 39 et 40, et le format interne devient JPS2. Les anciens JPS1 sont conservés ; les logiciels antérieurs doivent être mis à jour pour lire JPS2.

## Vérifications

- 48 nouvelles archives (quatre profils sur douze entrées), décodées dans de nouveaux processus avec des propriétés système différentes et comparées octet par octet.
- 84 vérifications de fichiers d'archives en comptant également les 24 anciens candidats et 12 références. Les références peuvent reprendre un ancien candidat : ce nombre n'est pas un décompte d'archives distinctes.
- Huit archives JPS1 réellement conservées de la version 21 sont encore restituées exactement.
- Les statistiques de parsing et les empreintes des coefficients sont identiques aux anciennes sur tous les candidats.
- Contrôle indépendant ImageIO des pixels sur les six fichiers du corpus progressif.
- Tests des profils 0 à 5 sur quatre cas synthétiques, y compris dimensions impaires, raffinement, multiscans, reprise et traîne ; 55 cas de rejet de données tronquées ou invalides ; comparaison du portefeuille sans régression de taille.
- Construction générale et tests des autres formats réussis.

La commande intégrée a été testée sur le chaton : sélection du profil 4, 76 496 octets, puis restitution exacte dans un autre processus. Sur le petit `image-rs_jpg-progressive.jpg`, elle conserve JPEG_IR à 884 octets, car les nouveaux profils sont plus gros. Le routeur peut donc garder une meilleure ancienne archive plutôt que forcer JPS2.

Ce sont les résultats de ces fichiers, pas un pourcentage garanti pour tous les JPEG progressifs. Les cas de développement dérivés ne sont pas comptés comme des images indépendantes. Le codec vise les JPEG Huffman à huit bits, SOF0/SOF1 multiscans et SOF2 progressifs. Ajouter les candidats augmente le temps de compression : sur le chaton, la comparaison des six profils du moteur progressif prend environ 7,3 secondes dans cet essai, contrôles de restitution inclus.

## Utiliser

```sh
bash build.sh
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli compress-enhanced progressive.jpg progressive.ivo
java -Xmx1g -cp target/classes io.ivo.cli.IvoCli decompress progressive.ivo restored.jpg
cmp progressive.jpg restored.jpg
```

Le moteur progressif participe maintenant au portefeuille explicite `compress-enhanced` ou `new Router(true)` ; le portefeuille standard reste celui d'avant. Le décompresseur standard lit JPS1 et JPS2. Le test porte aussi sur les multiscans pris en charge.

Pour reproduire tous les candidats sur une source :

```sh
java -Xmx1g -cp target/classes io.ivo.jpeg.ProgressiveMixerMeasure progressive.jpg resultats
```

Le programme mesure d'abord les références 0/1 et le routeur, puis les profils 2 à 5. Les données, journaux, tailles complètes, empreintes et archives retenues sont sous `research-results/2026-10-05/jpeg-progressive-mixer`. Les sources sont déjà présentes dans les dossiers référencés par `inputs.json`. Les candidats intermédiaires sont reproductibles et leurs empreintes sont conservées.
