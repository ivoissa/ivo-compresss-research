# MP3 — extension MPEG-2 / MPEG-2.5, 3 octobre 2026

## Explication simple

Le découpage par réservoir couvre maintenant MPEG-1, MPEG-2 et MPEG-2.5. Il conserve absolument tous les octets du MP3, y compris les données non utilisées par le décodage audio. Il ne convertit pas le son.

Sur les 24 fichiers du précédent corpus, les 13 fichiers MPEG-2/2.5 refusés auparavant sont maintenant acceptés. Sur ces 13 : 11 améliorations de taille et 2 augmentations. Le choix du plus petit résultat mesuré évite de retenir les transformations défavorables. Le gain supplémentaire total sur ce même corpus est de 3 423 octets ; ce n’est pas une percée en taux de compression.

Deux flux MPEG-2 stéréo supplémentaires, stockés sous extension .bit dans minimp3, ont été testés : test46 gagne 16 octets, test45 augmente de 3 octets pour sa meilleure variante V4.

Bilan des 26 fichiers/flux : 25 acceptés par V4, 19 améliorations et 6 augmentations comparées à V3. Les 26 fichiers sont restaurés exactement avec V1/V3 ; les 50 archives V4 acceptées sont également restaurées exactement. Un flux garde son refus pour intervalle de réservoir invalide.

Les chiffres « meilleur » ci-dessous sont des minima de laboratoire parmi STORE, V1, V3 et les variantes V4 disponibles, pas un routeur de production intégré. Les archives MP33/MP34 nécessitent le décodeur de recherche ; le CLI normal reste MP31.

## Mesures complètes

| Source / fichier | Original octets | V3 .ivo | V4 octets | V4 bits | Meilleur mesuré | Réduction |
|---|---:|---:|---:|---:|---:|---:|
| minimp3/performance/MEANDR_PHASE0.mp3 | 4001280 | 3894684 | 3894506 | 3892658 | 3892658 | 2.715% |
| minimp3/performance/MEANDR_PHASE90.mp3 | 401280 | 392857 | 392838 | 392662 | 392662 | 2.148% |
| minimp3/performance/MEANDR90.mp3 | 9601 | 9654 | refusé | refusé | 9653 | -0.542% |
| minimp3/performance/MIPSTest.mp3 | 328320 | 321043 | 321045 | 320911 | 320911 | 2.257% |
| minimp3/performance/noise_meandr.mp3 | 401280 | 392120 | 392086 | 392002 | 392002 | 2.312% |
| minimp3/fuzz/l3-compl-cut.mp3 | 192 | 284 | 287 | 287 | 251 | -30.729% |
| MelNet/music/sample-0.mp3 | 121869 | 116840 | 116773 | 116798 | 116773 | 4.182% |
| MelNet/music/sample-1.mp3 | 121869 | 117005 | 116879 | 116913 | 116879 | 4.095% |
| MelNet/music_primed/sample-0.mp3 | 121437 | 115558 | 115475 | 115444 | 115444 | 4.935% |
| MelNet/music_primed/sample-1.mp3 | 121437 | 115568 | 115371 | 115408 | 115371 | 4.995% |
| MelNet/ted_val/sample-0.mp3 | 82125 | 79294 | 79290 | 79323 | 79290 | 3.452% |
| MelNet/ted_val/sample-1.mp3 | 51885 | 49947 | 49818 | 49853 | 49818 | 3.984% |
| MelNet/blizzard_primed/sample-0.mp3 | 121671 | 118894 | 118848 | 118803 | 118803 | 2.357% |
| MelNet/blizzard_primed/sample-1.mp3 | 121671 | 118264 | 118246 | 118191 | 118191 | 2.860% |
| MelNet/wavenet_unconditional/maestro/sample-0.mp3 | 121044 | 117028 | 117288 | 117193 | 117028 | 3.318% |
| MelNet/wavenet_unconditional/maestro/sample-1.mp3 | 121044 | 117233 | 117454 | 117410 | 117233 | 3.148% |
| silence/1-second-of-silence.mp3 | 37206 | 28267 | 28254 | 28254 | 28254 | 24.061% |
| silence/10-seconds-of-silence.mp3 | 46208 | 36301 | 36284 | 36271 | 36271 | 21.505% |
| Samplelib/sample-3s.mp3 | 52079 | 48940 | 48840 | 48858 | 48840 | 6.219% |
| Samplelib/sample-6s.mp3 | 103070 | 97157 | 96914 | 96924 | 96914 | 5.973% |
| Samplelib/sample-9s.mp3 | 154062 | 121071 | 121201 | 121143 | 121070 | 21.415% |
| Samplelib/sample-speech-1m.mp3 | 961350 | 930196 | 928025 | 927616 | 927616 | 3.509% |
| Samplelib/sample-15s-cbr-128kbps.mp3 | 241206 | 217475 | 216672 | 216845 | 216672 | 10.171% |
| Samplelib/sample-15s-vbr-v2.mp3 | 220560 | 208018 | 208361 | 208117 | 208018 | 5.686% |
| minimp3/l3-test45.bit | 391105 | 346000 | 346003 | 346052 | 346000 | 11.533% |
| minimp3/l3-test46.bit | 130613 | 121849 | 121881 | 121833 | 121833 | 6.722% |

## Partie technique

### Lecture des informations latérales

| Champ | MPEG-1 | MPEG-2 / 2.5 |
|---|---|---|
| main_data_begin | 9 bits | 8 bits |
| private_bits mono / stéréo | 5 / 3 bits | 1 / 2 bits |
| scfsi | 4 bits par canal | absent |
| Granules par trame | 2 | 1 |
| scalefac_compress | 4 bits | 9 bits |
| preflag | 1 bit transmis | dérivé, aucun bit transmis |
| Taille side_info mono / stéréo | 17 / 32 octets | 9 / 17 octets |

Le module Java Layer3SideInfo lit part2_3_length (12 bits), valide big_values, suit les branches window_switching_flag, et exige la consommation exacte des side_info. Pour MPEG-2/2.5, le nombre de granules et les champs absents changent la lecture, sans modifier la formule des bornes.

Pour une trame i, O_i est le nombre d’octets des zones main_data physiques des trames précédentes, b_i est main_data_begin et l_i la somme des part2_3_length de ses granules/canaux :

```text
start_i = 8 * (O_i - b_i)
end_i   = start_i + l_i
U       = union des intervalles [start_i, end_i)
```

Le mode octets sépare les octets contenant au moins un bit de U et les autres. Le mode bits sépare chaque bit selon son appartenance à U. Chaque corde conserve l’ordre d’origine ; le remplissage de son dernier octet est dérivé de son nombre de bits. Le décodeur restaure en-têtes, layout et side_info, calcule le même masque U et remet chaque bit à sa place. Aucun masque n’est transmis.

Les codeurs restent ceux du précédent essai : RAW, zlib, et N&C ordre 7/contextes 5000 pour les cordes <=1 MiB. Cette extension augmente la couverture des formats ; elle n’ajoute pas un nouveau modèle probabiliste.

### Vérifications réalisées

- Écriture de chaque .ivo puis relecture, décodage, Arrays.equals et SHA-256. Taille du conteneur réellement écrite, avec nom, disposition, modes et intégrité.
- 24 fixtures indépendantes à offsets fixes, couvrant versions 1/2/2.5, mono/stéréo, branches longues/courtes, main_data_begin nul/maximal ; variantes avec offset non nul et cinq rejets de side_info invalides.
- Comparaison avec L3_read_side_info de minimp3 sur les 25 flux acceptés : même nombre de trames, même somme de part2_3_length et même somme de main_data_begin. La sélection des trames emploie la même politique de début/fin que le parseur IVO, pour comparer les mêmes octets ; la lecture des champs reste indépendante.
- Les 20 archives MPEG-1 du précédent corpus sont byte-identical avant/après extension.
- Les deux anciennes archives MP34 du fichier Tao de 95 634 889 octets sont décodées exactement par le nouveau code.
- Compilation Java 17 du projet complet, SelfTest et DeflateExactTest, puis tests du module side_info.

### Limites

Le découpage refuse les bornes négatives, dépassant la fin physique, ou les intervalles non vides qui se chevauchent. Le parseur historique ne prend pas en charge le débit libre (free-format). MPEG-2.5 réel est testé sur deux silences mono ; le cas stéréo est couvert par les fixtures de champs, pas par un corpus musical réel. Le petit fichier fuzz reste un cas où STORE gagne. Les extraits courts de Samplelib peuvent partager leur source ; les extraits MelNet/WaveNet sont synthétiques ou amorcés. Ce corpus ne constitue pas une validation universelle.

L’organisation en cordes réversibles, la prédiction contextuelle et REF ont des parentés avec les techniques de recompression structurelle, PPM et LZ. Aucune preuve d’originalité n’est revendiquée ici.

## Lancer en Java 17

```bash
bash build-reservoir.sh
java -Xmx1024m -cp target/reservoir-classes:target/classes io.ivo.mp3.CorpusTrial fichier.mp3 resultats
java -cp target/reservoir-classes:target/classes io.ivo.mp3.ReservoirTrial --reservoir-audit fichier.mp3
```

CorpusTrial écrit les archives V1/V3/V4 dans le répertoire resultats et vérifie leurs reconstructions. Les sources expérimentales sont dans research-experiments/mp3-reservoir/src ; le projet Maven standard est dans src avec pom.xml. Aucun JAR, fichier audio utilisateur ou corpus externe n’est inclus dans ce ZIP.

## Provenance

- https://github.com/lieff/minimp3
- https://github.com/anars/blank-audio
- https://github.com/audio-samples/audio-samples.github.io
- https://samplelib.com/sample-mp3.html

Les chemins sources et SHA-256 sont dans measurements.json. Les journaux, audits et comparaison indépendante sont joints.
- Commit /tmp/ivo-minimp3: ea99364f61c14656440e8d77e9c233ccf3124633
- Commit /tmp/ivo-audio-corpus: 92f06aaa1f1f4cae365af4a256b04cf9014de564
- Commit /tmp/ivo-musical-corpus: 80619088d77456ade8bee0a567a74ee6a22ef7c1
