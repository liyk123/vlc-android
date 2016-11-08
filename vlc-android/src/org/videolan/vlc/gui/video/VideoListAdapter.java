/*****************************************************************************
 * VideoListAdapter.java
 *****************************************************************************
 * Copyright © 2011-2012 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.gui.video;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.databinding.ViewDataBinding;
import android.preference.PreferenceManager;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.v7.util.SortedList;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;

import org.videolan.medialibrary.media.MediaWrapper;
import org.videolan.vlc.BR;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.gui.MainActivity;
import org.videolan.vlc.gui.SecondaryActivity;
import org.videolan.vlc.gui.helpers.UiTools;
import org.videolan.vlc.media.MediaGroup;
import org.videolan.vlc.media.MediaUtils;
import org.videolan.vlc.util.MediaItemFilter;
import org.videolan.vlc.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VideoListAdapter extends RecyclerView.Adapter<VideoListAdapter.ViewHolder> implements Filterable{

    public final static String TAG = "VLC/VideoListAdapter";

    public final static int SORT_BY_TITLE = 0;
    public final static int SORT_BY_LENGTH = 1;

    public final static int TYPE_LIST = 0;
    public final static int TYPE_GRID = 1;

    public final static int SORT_BY_DATE = 2;
    private boolean mListMode = false;
    private VideoGridFragment mFragment;
    private VideoComparator mVideoComparator = new VideoComparator();
    private volatile SortedList<MediaWrapper> mVideos = new SortedList<>(MediaWrapper.class, mVideoComparator);
    private ArrayList<MediaWrapper> mOriginalData = null;
    private ItemFilter mFilter = new ItemFilter();

    public VideoListAdapter(VideoGridFragment fragment) {
        super();
        mFragment = fragment;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        boolean listMode = viewType == TYPE_LIST;
        LayoutInflater inflater = (LayoutInflater) parent.getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(mListMode ? R.layout.video_list_card : R.layout.video_grid_card, parent, false);
        return new ViewHolder(v, listMode);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        MediaWrapper media = getItem(position);
        if (media == null)
            return;
        holder.binding.setVariable(BR.scaleType, ImageView.ScaleType.CENTER_CROP);
        fillView(holder, media);
        holder.binding.setVariable(BR.media, media);
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        holder.binding.setVariable(BR.cover, null);
        holder.binding.setVariable(BR.resolution, null);
        holder.binding.setVariable(BR.time, null);
        holder.binding.setVariable(BR.max, 0);
        holder.binding.setVariable(BR.progress, 0);
        holder.binding.executePendingBindings();
    }

    @MainThread
    public void setTimes( Map<Long, Long> times) {
        // update times
        for (int i = 0; i < getItemCount(); ++i) {
            MediaWrapper media = mVideos.get(i);
            Long time = times.get(media.getId());
            if (time != null && time != media.getTime()) {
                media.setTime(time);
                notifyItemChanged(getPosition(media));
            }
        }
    }

    private int getPosition(MediaWrapper media) {
        for (int i = 0; i < getItemCount(); ++i) {
            if (media.equals(getItem(i)))
                return i;
        }
        return -1;
    }

    public void sort() {
        if (!isEmpty())
            try {
                resetSorting();
            } catch (ArrayIndexOutOfBoundsException e) {} //Exception happening on Android 2.x
    }

    public boolean isEmpty()
    {
        return mVideos.size() == 0;
    }

    @Nullable
    public MediaWrapper getItem(int position) {
        if (position < 0 || position >= mVideos.size())
            return null;
        else
            return mVideos.get(position);
    }

    public void add(MediaWrapper item) {
        int position = mVideos.add(item);
        notifyItemInserted(position);
    }

    @MainThread
    public void remove(int position) {
        if (position == -1)
            return;
        mVideos.removeItemAt(position);
        notifyItemRemoved(position);
    }

    public void addAll(Collection<MediaWrapper> items) {
        mVideos.addAll(items);
        mOriginalData = null;
    }

    public boolean contains(MediaWrapper mw) {
        return mVideos.indexOf(mw) != -1;
    }

    public ArrayList<MediaWrapper> getAll() {
        int size = mVideos.size();
        ArrayList<MediaWrapper> list = new ArrayList<>(size);
        for (int i = 0; i < size; ++i)
            list.add(mVideos.get(i));
        return list;
    }

    @MainThread
    public void update(MediaWrapper item) {
        int position = mVideos.indexOf(item);
        if (position != -1) {
            if (!(mVideos.get(position) instanceof MediaGroup))
                mVideos.updateItemAt(position, item);
            notifyItemChanged(position);
        } else {
            position = mVideos.add(item);
            notifyItemRangeChanged(position, mVideos.size()-position);
        }
    }

    public void clear() {
        mVideos.clear();
        mOriginalData = null;
    }

    private void fillView(ViewHolder holder, MediaWrapper media) {
        String text = "";
        String resolution = "";
        int max = 0;
        int progress = 0;

        if (media.getType() == MediaWrapper.TYPE_GROUP) {
            MediaGroup mediaGroup = (MediaGroup) media;
            int size = mediaGroup.size();
            resolution = VLCApplication.getAppResources().getQuantityString(R.plurals.videos_quantity, size, size);
        } else {
            /* Time / Duration */
            if (media.getLength() > 0) {
                long lastTime = media.getTime();
                if (lastTime > 0) {
                    text = String.format("%s / %s",
                            Strings.millisToText(lastTime),
                            Strings.millisToText(media.getLength()));
                    max = (int) (media.getLength() / 1000);
                    progress = (int) (lastTime / 1000);
                } else {
                    text = Strings.millisToText(media.getLength());
                }
            }
            if (media.getWidth() > 0 && media.getHeight() > 0)
                resolution = String.format("%dx%d", media.getWidth(), media.getHeight());
        }

        holder.binding.setVariable(BR.resolution, resolution);
        holder.binding.setVariable(BR.time, text);
        holder.binding.setVariable(BR.max, max);
        holder.binding.setVariable(BR.progress, progress);
    }

    public void setListMode(boolean value) {
        mListMode = value;
    }

    public boolean isListMode() {
        return mListMode;
    }

    @Override
    public long getItemId(int position) {
        return 0L;
    }

    @Override
    public int getItemCount() {
        return mVideos.size();
    }

    @Override
    public int getItemViewType(int position) {
        return super.getItemViewType(position);
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener, View.OnFocusChangeListener {
        boolean listmode;
        public ViewDataBinding binding;

        public ViewHolder(View itemView, boolean listMode) {
            super(itemView);
            binding = DataBindingUtil.bind(itemView);
            this.listmode = listMode;
            binding.setVariable(BR.holder, this);
            itemView.setOnLongClickListener(this);
            itemView.setOnFocusChangeListener(this);
        }

        public void onClick(View v){
            MediaWrapper media = getItem(getAdapterPosition());
            if (media == null)
                return;
            Activity activity = mFragment.getActivity();
            if (media instanceof MediaGroup) {
                String title = media.getTitle().substring(media.getTitle().toLowerCase().startsWith("the") ? 4 : 0);
                ((MainActivity)activity).showSecondaryFragment(SecondaryActivity.VIDEO_GROUP_LIST, title);
            } else {
                media.removeFlags(MediaWrapper.MEDIA_FORCE_AUDIO);
                MediaUtils.openMedia(itemView.getContext(), media);
            }
        }

        public void onMoreClick(View v){
            if (mFragment == null)
                return;
            mFragment.mGridView.openContextMenu(getAdapterPosition());
        }

        @Override
        public boolean onLongClick(View v) {
            if (mFragment == null)
                return false;
            mFragment.mGridView.openContextMenu(getLayoutPosition());
            return true;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus)
                itemView.setBackgroundColor(UiTools.ITEM_FOCUS_ON);
            else
                itemView.setBackgroundColor(UiTools.ITEM_FOCUS_OFF);
        }
    }
    public int sortDirection(int sortDirection) {
        return mVideoComparator.sortDirection(sortDirection);
    }

    public void sortBy(int sortby) {
        mVideoComparator.sortBy(sortby);
    }

    private void resetSorting() {
        ArrayList<MediaWrapper> list = getAll();
        mVideos.clear();
        mVideos.addAll(list);
        notifyItemRangeChanged(0, mVideos.size());
    }

    public class VideoComparator extends SortedList.Callback<MediaWrapper> {

        private static final String KEY_SORT_BY =  "sort_by";
        private static final String KEY_SORT_DIRECTION =  "sort_direction";

        private int mSortDirection;
        private int mSortBy;
        protected SharedPreferences mSettings = PreferenceManager.getDefaultSharedPreferences(VLCApplication.getAppContext());

        public VideoComparator() {
            mSortBy = mSettings.getInt(KEY_SORT_BY, SORT_BY_TITLE);
            mSortDirection = mSettings.getInt(KEY_SORT_DIRECTION, 1);
        }
        public int sortDirection(int sortby) {
            if (sortby == mSortBy)
                return  mSortDirection;
            else
                return -1;
        }

        public void sortBy(int sortby) {
            switch (sortby) {
                case SORT_BY_TITLE:
                    if (mSortBy == SORT_BY_TITLE)
                        mSortDirection *= -1;
                    else {
                        mSortBy = SORT_BY_TITLE;
                        mSortDirection = 1;
                    }
                    break;
                case SORT_BY_LENGTH:
                    if (mSortBy == SORT_BY_LENGTH)
                        mSortDirection *= -1;
                    else {
                        mSortBy = SORT_BY_LENGTH;
                        mSortDirection *= 1;
                    }
                    break;
                case SORT_BY_DATE:
                    if (mSortBy == SORT_BY_DATE)
                        mSortDirection *= -1;
                    else {
                        mSortBy = SORT_BY_DATE;
                        mSortDirection *= 1;
                    }
                    break;
                default:
                    mSortBy = SORT_BY_TITLE;
                    mSortDirection = 1;
                    break;
            }
            resetSorting();

            SharedPreferences.Editor editor = mSettings.edit();
            editor.putInt(KEY_SORT_BY, mSortBy);
            editor.putInt(KEY_SORT_DIRECTION, mSortDirection);
            editor.apply();
        }

        @Override
        public int compare(MediaWrapper item1, MediaWrapper item2) {
            if (item1 == null)
                return item2 == null ? 0 : -1;
            else if (item2 == null)
                return 1;

            int compare = 0;
            switch (mSortBy) {
                case SORT_BY_TITLE:
                    compare = item1.getTitle().toUpperCase(Locale.ENGLISH).compareTo(
                            item2.getTitle().toUpperCase(Locale.ENGLISH));
                    break;
                case SORT_BY_LENGTH:
                    compare = ((Long) item1.getLength()).compareTo(item2.getLength());
                    break;
                case SORT_BY_DATE:
                    compare = ((Long) item1.getLastModified()).compareTo(item2.getLastModified());
                    break;
            }
            return mSortDirection * compare;
        }

        @Override
        public void onInserted(int position, int count) {}

        @Override
        public void onRemoved(int position, int count) {}

        @Override
        public void onMoved(int fromPosition, int toPosition) {}

        @Override
        public void onChanged(int position, int count) {}

        @Override
        public boolean areContentsTheSame(MediaWrapper oldItem, MediaWrapper newItem) {
            return areItemsTheSame(oldItem, newItem);
        }

        @Override
        public boolean areItemsTheSame(MediaWrapper item1, MediaWrapper item2) {
            if (item1 == item2)
                return true;
            if (item1 == null ^ item2 == null)
                return false;
            return item1.equals(item2);
        }
    }

    @Override
    public Filter getFilter() {
        return mFilter;
    }

    public void restoreList() {
        if (mOriginalData != null) {
            mVideos.clear();
            mVideos.addAll(mOriginalData);
            mOriginalData = null;
            notifyDataSetChanged();
        }
    }

    private class ItemFilter extends MediaItemFilter {

        @Override
        protected List<MediaWrapper> initData() {
            if (mOriginalData == null) {
                mOriginalData = new ArrayList<>(mVideos.size());
                for (int i = 0; i < mVideos.size(); ++i)
                    mOriginalData.add(mVideos.get(i));
            }
            return mOriginalData;
        }

        @Override
        protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
            mVideos.clear();
            mVideos.addAll((Collection<MediaWrapper>) filterResults.values);
            notifyDataSetChanged();
        }
    }
}
