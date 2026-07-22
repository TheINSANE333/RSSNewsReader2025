package xiangze.mmu.rssnewsreader.ui.about;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import dagger.hilt.android.AndroidEntryPoint;
import timber.log.Timber;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.FragmentAboutBinding;

@AndroidEntryPoint
public class AboutFragment extends Fragment {

    private FragmentAboutBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAboutBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Fetch and display package version dynamically
        String versionName = "1.0.0";
        try {
            if (getContext() != null) {
                PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                versionName = pInfo.versionName;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Timber.e(e, "Could not retrieve version name");
        }

        binding.aboutVersionText.setText(getString(R.string.about_version, versionName));

        binding.aboutContactEmail.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:" + getString(R.string.about_contact_email_value)));
            intent.putExtra(Intent.EXTRA_SUBJECT, "Text to Speech News Reader Support");
            try {
                startActivity(intent);
            } catch (Exception e) {
                Timber.e(e, "Could not launch email client");
            }
        });

        binding.aboutContactWebsite.setOnClickListener(v -> {
            String url = "https://TheINSANE333.github.io/RSSNewsReader2025/privacy.html";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Timber.e(e, "Could not launch web browser");
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
