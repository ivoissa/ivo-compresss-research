# Architecture IVO — Java 0.7.0, moteurs structurels et médias exacts

## Noyau effectivement implémenté

```text
CLI
 └─ Router
     ├─ STORE
     ├─ N&C Context
     ├─ REF -> N&C
     ├─ JSON lexical -> N&C
     ├─ DEFLATE IR exact
          ├─ zlib
          ├─ gzip
          └─ raw RFC1951 (.deflate/.dfl)
                │
     ├─ PDF V2
     │    ├─ JPEG V6.1 transversaux
     │    ├─ JPX regroupés bruts
     │    └─ Flate regroupés bruts
     ├─ ZIP/DOCX V1 (compatibilité/repli)
     ├─ ZIP/DOCX V3
          ├─ entrées regroupées par famille
          └─ champs ZIP communs factorisés
     └─ ZIP Bayes V1 / moteur 14
          ├─ données d'entrées agrégées
          ├─ blocs et arbres DEFLATE exacts
          ├─ décisions LZ77 différentielles
          └─ modèle bayésien contextuel
                │
                └─ IVO1 container + SHA-256

L'oracle JPEG exact et les moteurs structurels sont branchés au routeur.
Les identifiants 0 à 5 restent compatibles ; PDF V2 utilise 6, DOCX V1 utilise
7, DOCX V3 utilise 8 et ZIP Bayes V1 utilise 14. Les identifiants 9 à 13
restent ceux des moteurs JPEG, MP3 et MP4 publiés en 0.6.0.
```

Le routeur ne choisit jamais un moteur parce qu'il "devrait" être meilleur. Chaque candidat est réellement produit, redécodé et empaqueté. Le plus petit conteneur valide parmi les candidats comparés gagne. Le mode explicite `FAST` compare moins de profils JPEG et peut retenir immédiatement un moteur MP3/MP4 spécialisé vérifié qui bat STORE ; il peut donc manquer un meilleur candidat du portefeuille exhaustif. Voir `README_PERFORMANCE_FAST.md`.

## Couche DEFLATE

Le cœur RFC1951 est indépendant des wrappers :

```text
zlib/gzip/raw
      │
      ▼
  RFC1951 bits
      │
      ▼
parseur exact
      │
      ├─ META
      ├─ RUNS
      ├─ LITERALS
      ├─ LENGTHS
      └─ DISTANCES
```

Chaque corde choisit séparément parmi :

```text
STORE
N&C
REF -> N&C
```

Puis le réémetteur reconstruit les mêmes blocs, les mêmes arbres Huffman dynamiques, les mêmes tokens et les mêmes bits de remplissage final.

## Cibles suivantes

```text
FileProbe / structural detector
       │
       ├─ generic candidates
       │    ├─ STORE
       │    ├─ N&C
       │    └─ REF + Delta + N&C
       │
       └─ structural candidates
            ├─ PDF V2 et DOCX V3 (actifs)
            ├─ PNG -> IDAT/DEFLATE IR
            ├─ JSON/CSV avancé
            ├─ JPEG DCT
            ├─ MP3
            └─ MP4/video
```

La décision reste fondée sur le coût **réel**, métadonnées comprises.

## Stockage compact optionnel (v29)

`Router.Mode.COMPACT` reprend la sélection FAST et active temporairement les tables rapides `char[]` de `Ivo.CM`. Les tables lentes restent `int[]`. La portée est restaurée en cas d’exception et isolée par thread ; le format d’archive reste identique. Les autres modes gardent les tables rapides `int[]`. Voir `README_COMPACT_MIXER.md`.
