# DEFLATE IR exact — explication à deux niveaux

## Niveau 1 — l'idée simple

DEFLATE (utilisé notamment par zlib, gzip, ZIP, PNG et de nombreux flux PDF) ne stocke pas seulement des octets. Il alterne essentiellement :

- des octets écrits directement ;
- des instructions du type « retourne de `distance` octets en arrière et recopie `longueur` octets » ;
- des tables Huffman qui donnent des codes courts aux événements fréquents.

IVO démonte le flux en cinq séries plus homogènes :

```text
META       : blocs, tables Huffman et informations de reconstruction
RUNS       : combien de littéraux apparaissent entre deux références
LITERALS   : les octets écrits directement
LENGTHS    : longueurs des références
DISTANCES  : distances des références
```

L'intuition est qu'une série de distances se comporte statistiquement différemment d'une série de caractères. Les séparer donne au modèle une information structurelle sans perdre le fichier d'origine.

Chaque série est ensuite essayée telle quelle, avec N&C, ou avec `REF -> N&C`. Le plus petit choix est conservé.

Point essentiel : le but n'est pas simplement de retrouver les données décompressées. IVO doit retrouver **les octets du flux DEFLATE original**, même si un autre compresseur aurait produit un DEFLATE différent pour les mêmes données.

---

## Niveau 2 — représentation exacte RFC1951

### 1. Blocs

Un bloc commence par :

- `BFINAL` : 1 bit ;
- `BTYPE` : 2 bits.

Les valeurs supportées sont celles de RFC1951 :

- `00` : bloc stocké ;
- `01` : arbres Huffman fixes ;
- `10` : arbres Huffman dynamiques ;
- `11` : réservé, rejeté.

### 2. Arbres Huffman dynamiques

Pour `BTYPE=10`, le flux contient `HLIT`, `HDIST`, `HCLEN`, puis les longueurs de codes. Le parseur reconstruit les arbres canoniques nécessaires pour lire les tokens.

Pour préserver **exactement** la représentation d'origine, le prototype conserve aussi le bit-slice exact de l'en-tête Huffman dynamique. C'est volontaire : deux encodages valides des longueurs de codes peuvent définir les mêmes arbres tout en utilisant des bits différents.

### 3. Tokens longueur/distance

Pour un match :

\[
L = L_{base}(s_L) + e_L
\]

et

\[
D = D_{base}(s_D) + e_D
\]

où `s_L` et `s_D` sont les symboles Huffman et `e_L`, `e_D` les extra-bits RFC1951.

Dans l'IR, IVO conserve les valeurs réelles `L` et `D`. Lors de la reconstruction, le symbole et les extra-bits sont redérivés de manière déterministe.

### 4. Cinq cordes

Pour un bloc compressé comportant `m` matches, les littéraux sont représentés par `m+1` runs :

\[
r_0, r_1, \ldots, r_m
\]

avec :

\[
\sum_{i=0}^{m} r_i = N_{litteraux}.
\]

Les cordes sont donc :

\[
C = (M,R,B,L,D)
\]

avec `M=META`, `R=RUNS`, `B=LITERALS`, `L=LENGTHS`, `D=DISTANCES`.

Le coût du moteur est mesuré réellement :

\[
Cost(C_i)=\min\bigl(
|STORE(C_i)|,
|NC(C_i)|,
|NC(REF(C_i))|
\bigr).
\]

Le moteur DEFLATE n'est retenu par le routeur que si le conteneur IVO final est plus petit que les autres candidats.

### 5. Bits ignorés mais préservés

Après le symbole de fin du dernier bloc, quelques bits du dernier octet peuvent être inutilisés par le décompresseur. Ils font pourtant partie du fichier original. IVO enregistre leur valeur et leur nombre, puis les réécrit exactement.

Les blocs stockés conservent aussi leurs bits d'alignement ainsi que les champs `LEN` et `NLEN` originaux.

### 6. Invariant

Pour tout flux accepté par ce moteur :

\[
Emit(Parse(x)) = x.
\]

Ce test est effectué avant qu'une compression DEFLATE puisse devenir candidate dans le routeur.

## Limite actuelle

`DeflateIrEngine` traite directement les fichiers zlib, gzip mono-membre et raw-DEFLATE explicitement identifiés. Il ne démonte pas encore automatiquement :

- les entrées d'un ZIP/DOCX ;
- les `IDAT` PNG ;
- les streams `FlateDecode` d'un PDF.

Ces futurs moteurs réutiliseront `DeflateIrCodec` et pourront agréger les cinq cordes de nombreux flux avant N&C. C'est cette agrégation qui doit être benchmarkée lors de la phase suivante.
