# CABAC : décisions régulières — étape de recherche

## Explication simple

Le lecteur Java sait maintenant lire une décision CABAC avec un contexte adaptatif : il suit la probabilité du bit attendu et met ce contexte à jour après chaque lecture. Il sait aussi calculer un état initial à partir des coefficients m et n et du QP.

Cela ne constitue pas encore un compresseur vidéo. Il manque le choix des contextes selon la syntaxe réelle des macroblocs, les tables complètes de coefficients d’initialisation, puis un réémetteur exact. Aucun nouveau fichier .ivo plus petit n’a été produit : le meilleur résultat précédent sur la vidéo originale reste **5 414 825 octets**.

## Partie technique

Un contexte contient pStateIdx dans [0,63] et valMPS dans {0,1}. CabacTables conserve les 64 × 4 valeurs rangeTabLPS et les deux tables de transition de 64 entrées. Ces constantes normatives ont été recoupées avec la référence publique FFmpeg ; le code Java des opérations et des tests est propre à cette expérience.

Initialisation générique :

```
q = Clip3(0, 51, sliceQp)
pre = Clip3(1, 126, ((m*q) >> 4) + n)
si pre <= 63 : pStateIdx = 63-pre ; valMPS = 0
sinon        : pStateIdx = pre-64 ; valMPS = 1
```

Le décalage est arithmétique, y compris pour les coefficients négatifs. Le choix de m,n depuis slice_type et cabac_init_idc n’est pas encore implémenté.

Décision régulière : rLPS = rangeTabLPS[state][(range >>> 6) & 3] ; rMPS = range-rLPS. Si offset < rMPS, émettre valMPS et appliquer transIdxMPS. Sinon, soustraire rMPS de offset, prendre range=rLPS, émettre 1-valMPS et appliquer transIdxLPS ; inverser valMPS uniquement si l’ancien état était zéro. Renormaliser tant que range < 256 : doubler range et ajouter le bit suivant à offset doublé. Après une opération non terminale valide : 256 <= range <= 510 et 0 <= offset < range.

## Validation et limites

CabacRegularTest contient neuf cas fixes de décision, huit cas d’initialisation et un rejet d’état invalide. Les résultats attendus ont été calculés depuis les règles entières ; ce ne sont pas des traces produites par un codec externe. L’état 63 est couvert comme entrée de table, sans prétendre qu’il provient de l’initialisation ordinaire.

CabacIntervalTest représente le mot codé entier avec BigInteger, une borne inférieure absolue et une unité d’intervalle. Il compare bits décodés, range, offset, position de lecture et états des contextes à chaque opération sur 2 000 séquences synthétiques mêlant décisions régulières, bypass et terminaison. L’oracle partage les tables normatives avec le lecteur : il contrôle une formulation arithmétique différente, mais ne prouve pas indépendamment les constantes. Il a été écrit ici ; aucune validation par un codec tiers n’est revendiquée.

Les séquences ne suivent pas la syntaxe d’une slice H.264. Elles ne prouvent donc ni le décodage d’une vidéo réelle, ni decode(encode(X))=X pour une IR CABAC.

Reproduire : `bash build-cabac-research.sh`. Les journaux réels sont dans `research-results/2026-10-04/mp4-cabac-regular`. Sources Java 17 sous `research-experiments/mp4-cabac`, hors routeur automatique.

Références : H.264, clauses 9.3, https://www.itu.int/rec/T-REC-H.264 ; constantes numériques recoupées via https://www.ffmpeg.org/doxygen/0.5/cabac_8c.html .

## Prochaine étape

Relier l’initialisation aux SPS/PPS et aux en-têtes de slices, puis décoder un premier élément de syntaxe réel avec son contexte causal. Comparer à une référence externe avant d’étendre aux macroblocs complets. La réémission exacte précède toute annonce de gain des cordes CABAC.
