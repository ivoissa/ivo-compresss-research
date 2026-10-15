# Recherche JPEG : Nuage & Corde dans le domaine DCT

## Ce qui n'est pas l'innovation

Le découpage marqueurs/scans et la recompression générique des octets sont un
**oracle de réversibilité**. Ils servent à garantir `emit(parse(x)) = x`, mais
ne constituent pas le moteur de recherche. Sur quatre JPEG réels, ce découpage
ne gagne que 31 à 106 octets : il ne faut donc pas optimiser cette impasse.

## Hypothèse N&C-DCT

Un JPEG décrit des blocs de 64 coefficients quantifiés. Le JPEG classique suit
le zigzag à l'intérieur d'un bloc, puis code des couples `(run, magnitude)`.
Notre hypothèse est qu'une corde traversant plusieurs blocs de même fréquence
peut révéler une continuité que le codage bloc par bloc n'exploite pas.

On compare expérimentalement quatre trajectoires réversibles :

1. `ZIGZAG` : référence JPEG, bloc après bloc ;
2. `FREQUENCY_PLANE` : tous les coefficients d'une même fréquence DCT selon la
   géométrie 2D des blocs ;
3. `SPATIAL_SNAKE` : parcours serpentin des blocs pour éviter les ruptures de
   bord de ligne ;
4. `CROSS_COMPONENT` : contexte luminance Y complété par les coefficients Cb/Cr
   colocalisés, sans mélanger leurs alphabets.

Le nuage d'un coefficient utilise uniquement des informations déjà décodées :

```text
composante, fréquence, signe/magnitude du voisin gauche,
voisin haut, coefficient précédent de la corde, activité DC locale
```

Les DC sont traités séparément. Les prédicteurs à comparer sont : JPEG gauche,
médiane `(gauche, haut, diagonale)` et gradient `gauche + haut - diagonale`.

## Critère scientifique

Chaque trajectoire doit produire un conteneur complet et reconstruire le JPEG
octet pour octet. Elle n'est retenue que si elle améliore la taille médiane et
la taille totale sur un corpus indépendant. Les échecs et surcoûts sont gardés
dans le rapport ; aucune variante n'entre dans le routeur sur intuition seule.

## Étapes

- décoder DQT, SOF, DHT et SOS en coefficients quantifiés ;
- reconstruire exactement le scan Huffman original ;
- matérialiser les quatre trajectoires ;
- appliquer les mêmes modèles entiers à chaque trajectoire ;
- mesurer taille, temps, mémoire et gain par composante/fréquence ;
- intégrer uniquement la variante gagnante.
