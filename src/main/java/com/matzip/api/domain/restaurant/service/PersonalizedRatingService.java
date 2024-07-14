package com.matzip.api.domain.restaurant.service;

import com.matzip.api.common.event.DomainEventPublisher;
import com.matzip.api.domain.recommendation.entity.AspectPreference;
import com.matzip.api.domain.recommendation.entity.UserPreference;
import com.matzip.api.domain.recommendation.enums.RestaurantAspect;
import com.matzip.api.domain.recommendation.event.UserPreferenceRequestEvent;
import com.matzip.api.domain.recommendation.event.UserPreferenceUpdatedEvent;
import com.matzip.api.domain.restaurant.entity.Restaurant;
import com.matzip.api.domain.restaurant.entity.RestaurantCharacteristic;
import com.matzip.api.domain.review.entity.Review;
import com.matzip.api.domain.review.entity.ReviewRating;
import com.matzip.api.domain.review.service.ReviewViewService;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonalizedRatingService implements RatingService {

    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY = 100; // milliseconds

    private final Map<String, UserPreference> userPreferenceCache = new ConcurrentHashMap<>(); //TODO: redis로 변경 -> 사용자 로그인시 레디스에 사용자 선호도 등록처리
    private final ReviewViewService reviewViewService;
    private final DomainEventPublisher eventPublisher;

    @Override
    public double calculateRating(String userId, Restaurant restaurant) {
        UserPreference userPreference = getUserPreferenceWithRetry(userId);
        if (userPreference == null) {
            return getDefaultRating(restaurant);
        }

        List<Review> reviews = reviewViewService.getReviewsForRestaurant(restaurant.getId());

        double personalizedScore = calculatePersonalizedScore(userPreference, restaurant);
        double collaborativeScore = calculateCollaborativeScore(userId, reviews);

        return (personalizedScore + collaborativeScore) / 2.0;
    }

    private double getDefaultRating(Restaurant restaurant) {
        return 0.0; //TODO: 추후 구현
    }

    private UserPreference getUserPreferenceWithRetry(String userId) {
        UserPreference userPreference = userPreferenceCache.get(userId);
        if (userPreference == null) {
            eventPublisher.publish(new UserPreferenceRequestEvent(userId));
            for (int i = 0; i < MAX_RETRY; i++) {
                try {
                    Thread.sleep(RETRY_DELAY); // CompletableFuture로 처리하는게 나으려나
                    userPreference = userPreferenceCache.get(userId);
                    if (userPreference != null) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while getting user preference", e);
                    return null;
                }
            }
        }
        return userPreference;
    }

    private double calculatePersonalizedScore(UserPreference userPreference, Restaurant restaurant) {
        Map<String, Double> characteristicScores = restaurant.getCharacteristics().stream()
                .collect(Collectors.toMap(
                        characteristic -> characteristic.getAspect().name(),
                        RestaurantCharacteristic::getScore
                ));

        double personalizedScore = 0.0;
        double totalWeight = 0.0;

        for (AspectPreference aspectPreference : userPreference.getAspectPreferences()) {
            String aspect = aspectPreference.getAspect().name();
            double preferenceWeight = aspectPreference.getWeight();

            if (characteristicScores.containsKey(aspect)) {
                double characteristicScore = characteristicScores.get(aspect);
                personalizedScore += characteristicScore * preferenceWeight;
                totalWeight += preferenceWeight;
            }
        }

        return totalWeight > 0 ? personalizedScore / totalWeight : 0.0;
    }

    private double calculateCollaborativeScore(String userId, List<Review> restaurantReviews) {
        UserPreference userPreference = userPreferenceCache.get(userId);
        if (userPreference == null || restaurantReviews.isEmpty()) {
            return 0.0;
        }

        Map<String, Double> userSimilarities = calculateUserSimilarities(userId, restaurantReviews);
        return calculateAspectScores(userPreference, restaurantReviews, userSimilarities);
    }

    private double calculateAspectScores(UserPreference userPreference, List<Review> restaurantReviews,
                                         Map<String, Double> userSimilarities) {
        Map<RestaurantAspect, Double> aspectScores = new EnumMap<>(RestaurantAspect.class);
        Map<RestaurantAspect, Double> aspectWeights = new EnumMap<>(RestaurantAspect.class);

        for (RestaurantAspect aspect : RestaurantAspect.values()) {
            calculateAspectScoreForAspect(restaurantReviews, userPreference, userSimilarities, aspect, aspectScores,
                    aspectWeights);
        }

        return calculateWeightedAverage(aspectScores, aspectWeights);
    }

    private void calculateAspectScoreForAspect(List<Review> restaurantReviews, UserPreference userPreference,
                                               Map<String, Double> userSimilarities,
                                               RestaurantAspect aspect, Map<RestaurantAspect, Double> aspectScores,
                                               Map<RestaurantAspect, Double> aspectWeights) {
        double weightedSum = 0.0;
        double similaritySum = 0.0;

        for (Review review : restaurantReviews) {
            Double similarity = userSimilarities.get(review.getUser().getLoginId());
            if (similarity != null && similarity > 0) {
                Double rating = getAspectRating(review, aspect);
                if (rating != null) {
                    weightedSum += rating * similarity;
                    similaritySum += similarity;
                }
            }
        }

        if (similaritySum > 0) {
            aspectScores.put(aspect, weightedSum / similaritySum);
            aspectWeights.put(aspect, userPreference.getAspectScore(aspect));
        }
    }


    private Double getAspectRating(Review review, RestaurantAspect aspect) {
        return review.getRatings().stream()
                .filter(rating -> rating.getAspect() == aspect)
                .findFirst()
                .map(ReviewRating::getRating)
                .orElse(null);
    }

    private double calculateWeightedAverage(Map<RestaurantAspect, Double> scores,
                                            Map<RestaurantAspect, Double> weights) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (RestaurantAspect aspect : RestaurantAspect.values()) {
            Double score = scores.get(aspect);
            Double weight = weights.get(aspect);
            if (score != null && weight != null) {
                weightedSum += score * weight;
                totalWeight += weight;
            }
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    private Map<String, Double> calculateUserSimilarities(String userId, List<Review> restaurantReviews) {
        UserPreference userPreference = userPreferenceCache.get(userId);
        Map<String, Double> similarities = new HashMap<>();

        for (Review review : restaurantReviews) {
            String reviewerUsername = review.getUser().getLoginId();
            double similarity = calculateReviewSimilarity(userPreference, review);
            similarities.put(reviewerUsername, similarity);
        }

        return similarities;
    }

    private double calculateReviewSimilarity(UserPreference userPreference, Review review) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (ReviewRating rating : review.getRatings()) {
            RestaurantAspect aspect = rating.getAspect();
            double userScore = userPreference.getAspectScore(aspect);
            double reviewScore = rating.getRating();

            dotProduct += userScore * reviewScore;
            norm1 += userScore * userScore;
            norm2 += reviewScore * reviewScore;
        }

        norm1 = Math.sqrt(norm1);
        norm2 = Math.sqrt(norm2);

        return (norm1 > 0 && norm2 > 0) ? dotProduct / (norm1 * norm2) : 0.0;
    }

    @EventListener
    public void handleUserPreferenceUpdated(UserPreferenceUpdatedEvent event) {
        userPreferenceCache.put(event.getUsername(), event.getUserPreference());
    }
}