# MP3 : cordes par granule et canal — essai MP37

## Explication simple

Nous séparons les bits audio comprimés selon le canal, le granule, ou les deux. Les bits situés en dehors des segments déclarés sont conservés dans une corde distincte. Nous comprimons chaque corde, puis reconstruisons le fichier original sans réencoder le son.

Six variantes ont été réellement écrites en .ivo et relues. Le corpus de 26 fichiers donne cinq améliorations, soit 3 825 octets supplémentaires économisés après sélection du meilleur ancien ou nouveau candidat. Vingt fichiers compatibles ne gagnent pas ; un fichier au réservoir invalide est refusé et conserve son ancien candidat.

Le grand MP3 de 95 634 889 octets passe de 87 439 040 à 87 297 487 octets : gain supplémentaire de 141 553 octets (0,162 % du précédent conteneur). La variante par canal sans remplissage gagne ici. Les 156 nouveaux conteneurs ont été reconstruits et comparés directement octet pour octet, avec SHA-256 également vérifié. Il s'agit d'un prototype Java de recherche, pas encore du moteur du CLI de production.

## Détails techniques

Pour une trame i, O_i est le nombre d'octets main_data physiques précédents, b_i son main_data_begin et l_i,g,c son part2_3_length. Le début d'un segment est :

    s_i,g,c = 8*(O_i-b_i) + somme des longueurs des parties précédentes de la trame
    e_i,g,c = s_i,g,c + l_i,g,c

Les intervalles non vides doivent être bornés et disjoints. Les longueurs nulles sont admises. La complémentaire de leur union est stockée intégralement dans la corde unused, dans l'ordre physique initial. Aucun masque ni position n'est transmis : le décodeur les déduit des informations de trame conservées.

| Schéma | Cordes utilisées | Alignement |
|---|---|---|
| 0 | canal | bits continus |
| 1 | granule | bits continus |
| 2 | granule × canal | bits continus |
| 3 | canal | chaque partie complétée à l'octet |
| 4 | granule | chaque partie complétée à l'octet |
| 5 | granule × canal | chaque partie complétée à l'octet |

Le remplissage est nul, dérivé des longueurs et contrôlé à la lecture. Chaque corde choisit sa taille réellement encodée parmi RAW, zlib niveau 9 et N&C ordre 7 / 5 000 contextes pour les cordes de moins de 1 Mio. Tous les en-têtes, longueurs VarInt, choix de codeur, informations latérales, CRC, préfixes et suffixes font partie des tailles mesurées. Les cordes vides ont aussi un coût mesuré.

Le format expérimental MP37 conserve sept champs encadrés et un identifiant de format latéral. ReservoirTrial.decodeMainCandidate est son décodeur. Le CLI courant ne sait pas décoder MP37 : les essais réutilisent un identifiant de moteur existant et exigent le décodeur expérimental correspondant. La sélection des minima des rapports est une sélection de laboratoire, pas une intégration au routeur final.

Cette expérience regroupe des structures connues de MPEG Layer III avec un codage contextuel. Elle ne démontre ni nouveauté théorique ni gain universel. Elle travaille sur les bits Huffman existants, sans encore décoder les coefficients spectraux.

## Reproduction

Depuis la racine du projet, avec Java 17 :

```bash
bash build-reservoir.sh
java -Xmx2048m -cp target/reservoir-classes:target/classes io.ivo.mp3.MainMeasure original.mp3 meilleur-precedent.ivo resultats
```

Le précédent conteneur doit correspondre exactement au même original et être un candidat MP33, MP34, MP35 ou MP36. Le programme produit six .ivo complets puis vérifie chaque décompression. GranuleMainTest couvre 36 combinaisons MPEG1/2/2.5, mono/stéréo, réservoir, parties nulles, frontières non alignées et entrées malformées. Les journaux des tests généraux et spécialisés sont joints.

Les anciens chemins /tmp présents dans le manifeste désignent les sources du corpus historique. Pour cette exécution, les originaux ont été récupérés des conteneurs antérieurs validés et leur SHA-256 a été contrôlé avant comparaison. Les fichiers audio et les gros conteneurs de test ne sont pas inclus dans l'archive de sources.

La prochaine piste reste une représentation Huffman exacte des valeurs et décisions de codage ; elle n'a pas été testée dans ce rapport.
