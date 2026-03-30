package xiangze.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.squareup.picasso.Picasso;

import java.util.List;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class PreloadWorker extends Worker {
    private static final String TAG = "PreloadWorker";
    private final EntryRepository entryRepository;
    private final Context context;

    @AssistedInject
    public PreloadWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams, EntryRepository entryRepository) {
        super(context, workerParams);
        this.context = context;
        this.entryRepository = entryRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting PreloadWorker...");
        List<Entry> unpreloadedEntries = entryRepository.getUncachedEntries();
        
        if (unpreloadedEntries.isEmpty()) {
            Log.d(TAG, "No entries to preload.");
            return Result.success();
        }

        for (Entry entry : unpreloadedEntries) {
            String imageUrl = entry.getImageUrl();
            if (imageUrl != null && !imageUrl.isEmpty()) {
                Log.d(TAG, "Preloading image for entry: " + entry.getTitle());
                try {
                    // Picasso.fetch() downloads the image into the disk cache
                    Picasso.get().load(imageUrl).fetch();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to preload image: " + imageUrl, e);
                }
            }
            
            // Mark as cached regardless of image success (so we don't retry indefinitely)
            entryRepository.markAsCached(entry.getId());
        }

        return Result.success();
    }
}
