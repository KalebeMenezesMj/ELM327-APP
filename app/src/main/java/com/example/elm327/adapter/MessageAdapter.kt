package com.example.elm327.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.elm327.data.Message
import com.example.elm327.data.MessageType
import com.example.elm327.databinding.ItemMessageBinding

class MessageAdapter : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            binding.tvTimestamp.text = "[${message.timestamp}]"
            binding.tvMessage.text   = message.text

            val (textColor, tsColor, typeface) = styleFor(message.type)
            binding.tvMessage.setTextColor(textColor)
            binding.tvTimestamp.setTextColor(tsColor)
            binding.tvMessage.setTypeface(null, typeface)
        }

        /**
         * Retorna Triple(cor do texto, cor do timestamp, estilo da fonte)
         * para cada tipo de mensagem.
         *
         * Esquema de cores:
         *   SENT      → azul escuro  — comandos enviados pelo app
         *   RECEIVED  → preto        — dados normais do ELM327
         *   OK        → verde        — confirmações "OK"
         *   ERROR     → vermelho     — erros do ELM327
         *   WARNING   → laranja      — NODATA, STOPPED
         *   INFO      → azul médio   — SEARCHING, versão do ELM
         *   SYSTEM    → cinza itálico — mensagens internas do app
         */
        private fun styleFor(type: MessageType): Triple<Int, Int, Int> {
            return when (type) {
                MessageType.SENT     -> Triple(Color.parseColor("#1565C0"), Color.GRAY, Typeface.BOLD)
                MessageType.RECEIVED -> Triple(Color.parseColor("#212121"), Color.GRAY, Typeface.NORMAL)
                MessageType.OK       -> Triple(Color.parseColor("#2E7D32"), Color.GRAY, Typeface.NORMAL)
                MessageType.ERROR    -> Triple(Color.parseColor("#C62828"), Color.parseColor("#E57373"), Typeface.BOLD)
                MessageType.WARNING  -> Triple(Color.parseColor("#E65100"), Color.GRAY, Typeface.NORMAL)
                MessageType.INFO     -> Triple(Color.parseColor("#1976D2"), Color.GRAY, Typeface.NORMAL)
                MessageType.SYSTEM   -> Triple(Color.parseColor("#757575"), Color.parseColor("#BDBDBD"), Typeface.ITALIC)
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean =
            oldItem == newItem
    }
}
