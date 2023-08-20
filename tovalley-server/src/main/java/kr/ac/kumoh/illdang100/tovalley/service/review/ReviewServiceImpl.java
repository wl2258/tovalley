package kr.ac.kumoh.illdang100.tovalley.service.review;

import kr.ac.kumoh.illdang100.tovalley.domain.review.Review;
import kr.ac.kumoh.illdang100.tovalley.domain.review.ReviewImage;
import kr.ac.kumoh.illdang100.tovalley.domain.review.ReviewImageRepository;
import kr.ac.kumoh.illdang100.tovalley.domain.review.ReviewRepository;
import kr.ac.kumoh.illdang100.tovalley.domain.water_place.WaterPlace;
import kr.ac.kumoh.illdang100.tovalley.domain.water_place.WaterPlaceRepository;
import kr.ac.kumoh.illdang100.tovalley.handler.ex.CustomApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static kr.ac.kumoh.illdang100.tovalley.dto.page.PageRespDto.*;
import static kr.ac.kumoh.illdang100.tovalley.dto.review.ReviewRespDto.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final WaterPlaceRepository waterPlaceRepository;
    private final ReviewImageRepository reviewImageRepository;

    @Override
    public Review writeReview(Long memberId, Long waterPlaceId) {
        return null;
    }

    @Override
    public void addReviewImage(MultipartFile reviewImage) {

    }

    @Override
    public void deleteReviewImage(Long reviewImageId) {

    }

    @Override
    public Page<WaterPlaceReviewRespDto> getReviewsByMemberId(Long memberId) {
        return null;
    }

    /**
     * @methodnme: getReviewsByWaterPlaceId
     * @author: JYeonJun
     * @param waterPlaceId: 물놀이 장소 pk
     * @param pageable: 페이징 정보
     * @description: 물놀이 장소 리뷰 정보 조회
     * @return: 평점, 리뷰수, 리뷰 페이징 정보
     */
    @Override
    public WaterPlaceReviewDetailRespDto getReviewsByWaterPlaceId(Long waterPlaceId, Pageable pageable) {
        WaterPlace findWaterPlace = findWaterPlaceByWaterPlaceIdOrElseThrowEx(waterPlaceId);

        List<Review> allReviews = reviewRepository.findAllByWaterPlace_Id(waterPlaceId);

        Map<Integer, Long> ratingRatioMap = calculateRatingRatioMap(allReviews);

        Page<WaterPlaceReviewRespDto> reviewsByWaterPlaceId = reviewRepository.findReviewsByWaterPlaceId(waterPlaceId, pageable);

        double formattedRating = formatRating(findWaterPlace.getRating());

        includeReviewImages(reviewsByWaterPlaceId.getContent());

        return new WaterPlaceReviewDetailRespDto(formattedRating, findWaterPlace.getReviewCount(), ratingRatioMap, reviewsByWaterPlaceId);
    }

    private Map<Integer, Long> calculateRatingRatioMap(List<Review> reviews) {
        Map<Integer, Long> ratingRatioMap = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            ratingRatioMap.put(i, 0L);
        }

        reviews.forEach(review -> {
            int rating = review.getRating();
            ratingRatioMap.put(rating, ratingRatioMap.getOrDefault(rating, 0L) + 1);
        });

        return ratingRatioMap;
    }

    private void includeReviewImages(List<WaterPlaceReviewRespDto> reviews) {
        for (WaterPlaceReviewRespDto review : reviews) {
            Long reviewId = review.getReviewId();
            List<ReviewImage> reviewImages = reviewImageRepository.findByReview_Id(reviewId);
            List<String> reviewStoreFileUrls = reviewImages.stream()
                    .map(image -> image.getImageFile().getStoreFileUrl())
                    .collect(Collectors.toList());
            review.setReviewImages(reviewStoreFileUrls);
        }
    }


    private WaterPlace findWaterPlaceByWaterPlaceIdOrElseThrowEx(Long waterPlaceId) {
        WaterPlace findWaterPlace = waterPlaceRepository.findById(waterPlaceId)
                .orElseThrow(() -> new CustomApiException("물놀이 장소[" + waterPlaceId + "]가 존재하지 않습니다"));
        return findWaterPlace;
    }

    private double formatRating(double rating) {
        return Double.parseDouble(String.format("%.1f", rating));
    }
}