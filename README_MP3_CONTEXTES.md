> Mise à jour : MP39/MP40 sont désormais intégrés au CLI principal. Voir `README_INTEGRATION_MP3.md`. Ce rapport conserve les mesures historiques et décrit leur contexte au moment des essais.

# MP40 — contextes causaux des amplitudes MP3

## Explication simple

MP40 conserve le même fichier MP3, octet pour octet. Il ajoute au portefeuille MP39 des cordes d’amplitudes regroupées selon des informations que le décodeur connaît déjà : gain global, type de bloc et moyenne des facteurs d’échelle effectivement transmis. Il ne réencode pas le son et ne transmet pas une seconde fois ces informations.

Huit variantes ont été écrites, puis les **104 archives de 13 fichiers ont été rouvertes dans un nouveau processus**, restituées et comparées directement aux originaux. Quatre fichiers améliorent le meilleur précédent ; neuf conservent leur candidat précédent. Le projet ajoute ce candidat de recherche, sans retirer MP39 ni modifier le routeur principal MP31.

Le grand fichier donne **82 851 262 octets**, contre **83 508 548 avec MP39** : 657 286 octets supplémentaires, soit 0,79 % du précédent conteneur. Le type de bloc seul donne 82 909 977 octets. La combinaison ajoute donc 58 715 octets de gain à ce dernier résultat. Ces chiffres incluent tout le conteneur, ses métadonnées, son nom et son SHA-256.

Les mesures complètes et leurs traces se trouvent dans `research-results/2026-10-04/mp3-contexts`. Une valeur négative de gain supplémentaire indique qu’il faut conserver le meilleur précédent. Le petit fichier de fuzz conserve STORE.


| Fichier | Ancien meilleur .ivo | MP40 meilleur .ivo | Gain supplémentaire |
|---|---:|---:|---:|
| MEANDR_PHASE0.mp3 | 3 795 012 | 3 759 232 | +35 780 |
| MIPSTest.mp3 | 314 563 | 311 621 | +2 942 |
| generated-22050-1.mp3 | 8 415 | 8 788 | -373 |
| generated-22050-2.mp3 | 7 178 | 7 781 | -603 |
| generated-44100-1.mp3 | 13 474 | 13 257 | +217 |
| generated-44100-2.mp3 | 12 078 | 12 231 | -153 |
| generated-8000-1.mp3 | 6 169 | 6 529 | -360 |
| generated-8000-2.mp3 | 5 188 | 5 872 | -684 |
| l3-compl-cut.mp3 | 251 | 499 | -248 |
| l3-test45.bit | 325 370 | 327 455 | -2 085 |
| l3-test46.bit | 109 287 | 119 509 | -10 222 |
| noise_meandr.mp3 | 390 162 | 391 976 | -1 814 |
| Tao | 83 508 548 | 82 851 262 | +657 286 |

## Partie technique

### Contextes et causalité

`SpectralIr.side` lit maintenant aussi le champ `global_gain`, sur les huit bits déjà présents dans le side_info conservé. Les 16 lectures de champs de deux granules dans des fixtures à offsets connus sont vérifiées par `ContextSchemeTest`. Le décodeur lit tous les facteurs d’échelle transmis avant les amplitudes d’une partie ; il dispose donc des mêmes valeurs que l’encodeur pour former le contexte.

Ce premier essai utilise un **résumé de partie**, pas le facteur d’échelle effectif de chaque bande :

`mean = floor(sum(facteurs effectivement transmis) / nombre de ces facteurs)`

S’il n’y a aucun facteur transmis, `mean=0`. Les facteurs réutilisés par scfsi ne sont pas ajoutés à cette moyenne. Les largeurs et valeurs zéro omises par la syntaxe restent traitées comme dans MP39. Il ne faut donc pas interpréter ce résumé comme une déquantification ou une reconstruction complète des facteurs d’échelle par bande.

Les quatre partitions, fixées avant la mesure, sont :

| Partition | Classes |
|---|---|
| Gain global | `<180`, `[180,200)`, `[200,220)`, `≥220` |
| Type de bloc | long/début/fin ; court pur ; court mixte |
| Moyenne transmise | `<2`, `[2,4)`, `[4,8)`, `≥8` |
| Combinaison | `(gain≥210, mean≥4, bloc court)` : huit classes binaires |

Les positions sont les plages MP39 `[0,16)`, `[16,64)`, `[64,256)` et `[256,576)` dans l’ordre des valeurs Huffman. Ce ne sont pas des bandes psychoacoustiques après réordonnancement des blocs courts.

La corde d’amplitudes est sélectionnée par :

`index = nombreCordesDeBase + (canal*4 + plagePosition)*nombreClasses + classeContexte`

MAGNITUDE stocke toujours VarInt de `abs(v)-1` pour chaque valeur non nulle. La reconstruction reste `v=(-1)^s*(u+1)`. SUPPORT, SIGN, QUAD, CONTROL, SCALE, UNUSED, RESIDUAL et les métadonnées ne changent pas de représentation.

### Variantes et coût

| Schéma | Base des autres cordes | Partition MAGNITUDE | Cordes totales |
|---:|---|---|---:|
| 4 | MP39 position/canal (2) | Gain | 72 |
| 5 | MP39 position/canal (2) | Bloc | 64 |
| 6 | MP39 position/canal (2) | Moyenne transmise | 72 |
| 7 | MP39 position/canal (2) | Combinaison | 104 |
| 8 | MP39 globale (0) | Gain | 40 |
| 9 | MP39 globale (0) | Bloc | 32 |
| 10 | MP39 globale (0) | Moyenne transmise | 40 |
| 11 | MP39 globale (0) | Combinaison | 72 |

Les anciennes cordes MAGNITUDE de la base sont explicitement vides. Leur coût, celui des nouvelles cordes et celui des classes vides sont comptés. Les autres cordes codées sont reprises de l’archive MP39 de même original, ce qui conserve exactement leurs modèles et métadonnées. Le portefeuille de codeurs par nouvelle corde reste celui de MP39, avec ses limites de calcul documentées.

Pour accélérer les essais appariés 4/8, 5/9, 6/10, 7/11, le codage des amplitudes est réutilisé uniquement après vérification de l’identité des cordes brutes par SHA-256. Cette réutilisation ne change ni la représentation ni les tailles. Les temps des variantes appariées sont donc des temps avec cache, pas une comparaison de performances à froid.

Le payload est identifié par `MP40`, avec l’identifiant de moteur expérimental local 19. Il conserve les sept champs encadrés de MP39 et le format side_info ; le champ spectral porte le schéma, le nombre de cordes puis leurs données codées. Le décodeur `CordsMeasure` fourni accepte MP39 et MP40 et vérifie la cohérence signature/schéma. Le CLI principal ne connaît pas le moteur de recherche : il ne faut pas lui confier la décompression d’une archive MP40.

L’archive finale est autonome. Les archives MP39 servent à construire le candidat, pas à le décompresser. Aucun dictionnaire externe ni original MP3 n’est nécessaire au décodeur.

### Validation et interprétation

Chaque candidat a passé `Arrays.equals(original, decode(encode(original)))`, la longueur, le SHA-256 et l’égalité des statistiques spectrales. Le validateur final ouvre les 104 archives dans un nouveau processus. Ses comptes et empreintes des valeurs correspondent aux goldens MP38 précédents, contrôlés par minimp3 sur douze des treize sources. Les tests Huffman, scalefactors, réservoir, codeurs et contrôles des entrées malformées précédents passent aussi.

Les six signaux synthétiques, les six vecteurs publics et la grande lecture restent un corpus limité. Cette mesure ne démontre pas une amélioration moyenne sur toute la musique.

Le gain global grossièrement partitionné perd ici sur le grand fichier. Dans la combinaison gagnante de ce fichier, les cordes contenant des amplitudes pour `gain≥210` sont vides : le résultat ne démontre pas un bénéfice de cette composante. Le gain observé provient surtout du type de bloc, complété par le résumé des facteurs transmis. Des classes de gain plus fines restent une piste non testée dans ce rapport.

La partition contextuelle, le codage arithmétique, REF/LZ et les modèles à échappement apparentés à PPM sont des briques connues. L’expérience porte sur leur combinaison, leur coût réel et la conservation exacte des décisions MP3 ; elle ne prétend pas établir une nouveauté théorique.

### Reproduction

```bash
bash build-reservoir.sh
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsMeasure original.mp3 base-mp39 0,2
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.ContextMeasure original.mp3 base-mp39/spectral-s0.ivo base-mp39/spectral-s2.ivo resultats-mp40 4,5,6,7,8,9,10,11
```

Comparer les tailles complètes aux candidats précédents ; ne pas utiliser systématiquement MP40. Pour décompresser le candidat 7 sans l’original ni les bases MP39 :

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.CordsMeasure decode resultats-mp40/context-s7.ivo restaure.mp3 travail
```

Pour reproduire le contrôle final d’un corpus organisé comme celui de ce rapport :

```bash
java -Xmx2048m -cp target/classes:target/reservoir-classes io.ivo.mp3.ContextArchiveValidation resultats-mp40 dossier-petits-mp3 grand-original.mp3 validation-finale.txt
```

Le validateur attend un sous-dossier nommé comme chaque petit original, et `tao` pour le grand fichier. Il écrit son rapport complet après les vérifications et s’arrête au premier échec. Sur Windows, le séparateur du classpath est `;`.
