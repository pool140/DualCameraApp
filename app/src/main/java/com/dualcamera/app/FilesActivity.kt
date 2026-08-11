package com.dualcamera.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        val recycler = findViewById<RecyclerView>(R.id.recyclerFiles)
        recycler.layoutManager = LinearLayoutManager(this)

        val files = getVideoFiles()
        recycler.adapter = FilesAdapter(files) { file ->
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setItems(arrayOf("تشغيل", "حذف", "الغاء")) { _, which ->
                    when (which) {
                        0 -> playVideo(file)
                        1 -> deleteFile(file, recycler, files)
                    }
                }.show()
        }

        if (files.isEmpty()) {
            findViewById<TextView>(R.id.tvEmpty).visibility = View.VISIBLE
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun getVideoFiles(): MutableList<File> {
        val dirs = mutableListOf<File>()
        getExternalFilesDir(null)?.let { dirs.add(it) }
        File(filesDir, "hidden_videos").let { if (it.exists()) dirs.add(it) }
        val files = mutableListOf<File>()
        dirs.forEach { dir -> dir.listFiles()?.filter { it.extension == "mp4" }?.let { files.addAll(it) } }
        return files.sortedByDescending { it.lastModified() }.toMutableList()
    }

    private fun playVideo(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(file), "video/mp4")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun deleteFile(file: File, recycler: RecyclerView, files: MutableList<File>) {
        AlertDialog.Builder(this)
            .setTitle("حذف الملف")
            .setMessage("هل تريد حذف " + file.name + "؟")
            .setPositiveButton("حذف") { _, _ ->
                file.delete()
                files.remove(file)
                recycler.adapter?.notifyDataSetChanged()
            }
            .setNegativeButton("الغاء", null)
            .show()
    }
}

class FilesAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<FilesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val tvType: TextView = view.findViewById(R.id.tvFileType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.tvName.text = file.name
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val date = sdf.format(Date(file.lastModified()))
        val size = String.format("%.1f MB", file.length() / 1024.0 / 1024.0)
        holder.tvInfo.text = "$date - $size"
        holder.tvType.text = if (file.name.startsWith("FRONT")) "أمامية" else "خلفية"
        holder.tvType.setTextColor(
            if (file.name.startsWith("FRONT")) 0xFF00D4FF.toInt() else 0xFF10B981.toInt()
        )
        holder.itemView.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = files.size
}
