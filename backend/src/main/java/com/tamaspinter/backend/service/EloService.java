package com.tamaspinter.backend.service;

import java.util.*;

public class EloService {
    private static final double K = 32.0;

    public static Map<String, Double> updateRatings(Map<String, Double> current, Map<String, Double> scores) {
        Map<String, Double> updated = new HashMap<>();
        for (String id : current.keySet()) {
            double R = current.get(id);
            double S = scores.getOrDefault(id, 0.0);
            double E = 0.0;
            for (String opp : current.keySet())
                if (!opp.equals(id)) {
                    double Ropp = current.get(opp);
                    E += 1 / (1 + Math.pow(10, (Ropp - R) / 400));
                }
            E /= (current.size() - 1);
            double Rnew = R + K * (S - E);
            updated.put(id, Rnew);
        }
        return updated;
    }
}