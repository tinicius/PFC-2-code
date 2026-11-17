package org.sbpo2025.challenge;

public class MaxSubsetSum {

    int[][] DP;

    public int maxSubsetSum(int[] arr, int idx, int n, int cap) {
        if (cap < 0) return Integer.MIN_VALUE;
        if (cap == 0 || idx >= n) return 0;

        if (DP[idx][cap] != -1) return DP[idx][cap];

        int skip = maxSubsetSum(arr, idx + 1, n, cap); 
        int take = arr[idx] + maxSubsetSum(arr, idx + 1, n, cap - arr[idx]); 

        DP[idx][cap] = Math.max(skip, take);
        return DP[idx][cap];
    }

    public int solve(int[] coefs, int n, int limit) {
        DP = new int[n][limit + 1];
        
        for (int i = 0; i < n; i++)
            for (int j = 0; j <= limit; j++)
                DP[i][j] = -1;

        return maxSubsetSum(coefs, 0, n, limit);
    }
}
