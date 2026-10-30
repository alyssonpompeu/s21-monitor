/* Apple Final V5 — proposed/geometric MIF OPP table. NOT validated for physical LPDDR5. */
/* These CAL/ASV estimates must NOT be used as direct DRAM VDDQ/VDD2 setpoints. */
struct apple_mif_v5_candidate_opp { unsigned int freq_khz, estimated_cal_asv_uv; };
static const struct apple_mif_v5_candidate_opp apple_mif_v5_candidates[13] = {
    { 3672000U, 800000U }, /* level 0; EXTRAPOLADO */
    { 3066000U, 768750U }, /* level 1; INTERPOLADO */
    { 2559000U, 743750U }, /* level 2; INTERPOLADO */
    { 2137000U, 700000U }, /* level 3; INTERPOLADO */
    { 1784000U, 656250U }, /* level 4; INTERPOLADO */
    { 1489000U, 618750U }, /* level 5; INTERPOLADO */
    { 1243000U, 587500U }, /* level 6; INTERPOLADO */
    { 1038000U, 550000U }, /* level 7; INTERPOLADO */
    { 867000U, 537500U }, /* level 8; INTERPOLADO */
    { 723000U, 531250U }, /* level 9; INTERPOLADO */
    { 604000U, 518750U }, /* level 10; INTERPOLADO */
    { 504000U, 512500U }, /* level 11; INTERPOLADO */
    { 421000U, 506250U }, /* level 12; OBSERVADO */
};
