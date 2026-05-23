package xiangze.mmu.rssnewsreader.ui.feedsetting;

import androidx.lifecycle.ViewModel;

import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class FeedSettingViewModel extends ViewModel {

    private FeedRepository feedRepository;

    @Inject
    public FeedSettingViewModel(FeedRepository feedRepository) {
        this.feedRepository = feedRepository;
    }

    public void updateFeedSettings(String title, String desc, String language, boolean autoSummarize, boolean autoTranslate, int delayTime, float ttsSpeechRate, String link) {
        feedRepository.updateFeedSettings(title, desc, language, autoSummarize, autoTranslate, delayTime, ttsSpeechRate, link);
    }
}
